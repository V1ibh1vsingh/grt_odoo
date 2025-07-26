package org.example;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.logging.*;

/**
 * DynamicPricingService.java
 *
 * A single-file Java 17 microservice that exposes a Dynamic Pricing Engine over HTTP.
 * It demonstrates:
 *  - Modular, priority-based pricing rules (combinable & exclusive)
 *  - Pluggable demand forecasting strategies
 *  - Integration with live data feeds for occupancy, competitor prices, base rates
 *  - REST endpoints to query or preview prices
 *  - Thread-safe in-memory state
 *  - Rich audit trail for pricing transparency
 *  - Embedded unit tests runnable via "java -ea DynamicPricingService test"
 *
 * NOTE:
 *  - Kept dependency-free (uses only JDK) for "single-file" requirement.
 *  - Uses the built-in HttpServer – good enough to front with a proxy in prod.
 *  - JSON is produced with a simple, safe serializer implemented below.
 *  - For real production use: swap HttpServer with your framework of choice, add auth, schema validation, metrics, etc.
 */
public class DynamicPricingService {

    // ====== CONFIG ======
    private static final int DEFAULT_PORT = 8080;

    // ====== LOGGING ======
    private static final Logger LOG = Logger.getLogger(DynamicPricingService.class.getName());

    static {
        // Simple console logger configuration
        LogManager.getLogManager().reset();
        ConsoleHandler ch = new ConsoleHandler();
        ch.setLevel(Level.INFO);
        ch.setFormatter(new SimpleFormatter() {
            private static final String format = "[%1$tF %1$tT %2$-7s] %3$s %n";
            @Override public synchronized String format(LogRecord lr) {
                return String.format(format,
                        Instant.ofEpochMilli(lr.getMillis()).atZone(ZoneId.systemDefault()).toLocalDateTime(),
                        lr.getLevel().getLocalizedName(),
                        lr.getMessage());
            }
        });
        LOG.addHandler(ch);
        LOG.setLevel(Level.INFO);
    }

    // ====== DATA MODELS ======

    /** Immutable user segment enumeration. Extend as needed. */
    public enum UserSegment {
        GUEST, MEMBER, SILVER, GOLD, PLATINUM, CORPORATE, LOYAL
    }

    /** Context fed to the pricing engine – the whole world it needs to know. */
    public static final class PricingContext {
        public final String hotelId;
        public final String roomType;
        public final LocalDate checkIn;
        public final int nights;
        public final UserSegment segment;
        public final double basePrice; // If not provided, falls back to feeds
        public final double occupancy; // 0..1
        public final Double competitorPrice; // nullable
        public final double seasonMultiplier; // e.g. 1.2 for peak
        public final long bookingWindowDays; // days between now and check-in
        public final double demandIndex; // 0..1 (forecast model output, higher = more demand)

        public PricingContext(String hotelId,
                              String roomType,
                              LocalDate checkIn,
                              int nights,
                              UserSegment segment,
                              double basePrice,
                              double occupancy,
                              Double competitorPrice,
                              double seasonMultiplier,
                              long bookingWindowDays,
                              double demandIndex) {
            this.hotelId = Objects.requireNonNull(hotelId);
            this.roomType = Objects.requireNonNull(roomType);
            this.checkIn = Objects.requireNonNull(checkIn);
            this.nights = nights;
            this.segment = Objects.requireNonNull(segment);
            this.basePrice = basePrice;
            this.occupancy = occupancy;
            this.competitorPrice = competitorPrice;
            this.seasonMultiplier = seasonMultiplier;
            this.bookingWindowDays = bookingWindowDays;
            this.demandIndex = demandIndex;
        }
    }

    /** Captures the impact a rule had on the price, for full transparency. */
    public static final class AppliedRule {
        public final String name;
        public final int priority;
        public final boolean exclusive;
        public final double before;
        public final double after;
        public final Map<String, Object> metadata;

        public AppliedRule(String name, int priority, boolean exclusive,
                           double before, double after, Map<String, Object> metadata) {
            this.name = name;
            this.priority = priority;
            this.exclusive = exclusive;
            this.before = before;
            this.after = after;
            this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("priority", priority);
            m.put("exclusive", exclusive);
            m.put("before", round(before));
            m.put("after", round(after));
            m.put("metadata", metadata);
            return m;
        }
    }

    /** The result from the pricing engine. */
    public static final class PriceComputation {
        public final double base;
        public final double finalPrice;
        public final double demandIndex;
        public final List<AppliedRule> appliedRules;
        public final List<String> auditTrail;

        public PriceComputation(double base,
                                double finalPrice,
                                double demandIndex,
                                List<AppliedRule> appliedRules,
                                List<String> auditTrail) {
            this.base = base;
            this.finalPrice = finalPrice;
            this.demandIndex = demandIndex;
            this.appliedRules = List.copyOf(appliedRules);
            this.auditTrail = List.copyOf(auditTrail);
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("basePrice", round(base));
            m.put("finalPrice", round(finalPrice));
            m.put("demandIndex", round(demandIndex));
            List<Object> rules = new ArrayList<>();
            for (AppliedRule r : appliedRules) rules.add(r.toJson());
            m.put("appliedRules", rules);
            m.put("auditTrail", auditTrail);
            return m;
        }
    }

    // ====== ENGINE CONTRACTS ======

    /** Rule contract – implement for new pricing behaviors. */
    public interface PricingRule {
        String name();
        int priority();              // higher = earlier execution
        boolean exclusive();         // if true, stop further rule processing after apply
        boolean applies(PricingContext ctx);
        double apply(PricingContext ctx, double currentPrice, List<String> audit, Map<String,Object> metaOut);
    }

    /** Demand forecasting contract – plug your ML model here. */
    public interface DemandForecaster {
        String name();
        /**
         * Returns a demand index in [0, 1]. Higher == more demand.
         * You can make it >1 if your rules know how to clamp/interpret, but keep [0,1] for simplicity.
         */
        double forecast(DemandContext ctx);
    }

    /** Context for forecasting. Add any time series or features you ingest. */
    public static final class DemandContext {
        public final String hotelId;
        public final String roomType;
        public final LocalDate checkIn;
        public final long bookingWindowDays;
        public final double currentOccupancy;
        public final Double competitorPrice;

        public DemandContext(String hotelId, String roomType, LocalDate checkIn,
                             long bookingWindowDays, double currentOccupancy,
                             Double competitorPrice) {
            this.hotelId = hotelId;
            this.roomType = roomType;
            this.checkIn = checkIn;
            this.bookingWindowDays = bookingWindowDays;
            this.currentOccupancy = currentOccupancy;
            this.competitorPrice = competitorPrice;
        }
    }

    /** The rule engine – sorts by priority, applies rules, respects exclusivity. */
    public static final class RuleEngine {
        private final List<PricingRule> rules;

        public RuleEngine(List<PricingRule> rules) {
            // sort descending by priority
            this.rules = new ArrayList<>(rules);
            this.rules.sort(Comparator.comparingInt(PricingRule::priority).reversed());
        }

        public PriceComputation execute(PricingContext ctx, DemandForecaster forecaster) {
            List<String> audit = new ArrayList<>();
            List<AppliedRule> applied = new ArrayList<>();

            double price = ctx.basePrice * ctx.seasonMultiplier;
            double demandIndex = ctx.demandIndex; // Already computed (for transparency), still store

            audit.add(String.format("Start base: %.2f; seasonMultiplier: %.2f => %.2f",
                    ctx.basePrice, ctx.seasonMultiplier, price));

            for (PricingRule r : rules) {
                if (!r.applies(ctx)) {
                    audit.add(String.format("Rule [%s] skipped (not applicable)", r.name()));
                    continue;
                }
                Map<String,Object> meta = new LinkedHashMap<>();
                double before = price;
                price = r.apply(ctx, price, audit, meta);
                applied.add(new AppliedRule(r.name(), r.priority(), r.exclusive(), before, price, meta));
                audit.add(String.format("Rule [%s] applied: %.2f -> %.2f", r.name(), before, price));

                if (r.exclusive()) {
                    audit.add(String.format("Rule [%s] is exclusive. Stopping further rule processing.", r.name()));
                    break;
                }
            }

            // Clamp & round
            price = round(price);

            return new PriceComputation(round(ctx.basePrice), price, demandIndex, applied, audit);
        }
    }

    // ====== DEFAULT RULES ======

    /** Surge pricing if occupancy or demand index above thresholds. */
    public static final class SurgePricingRule implements PricingRule {
        private final double occupancyThreshold;
        private final double demandThreshold;
        private final double multiplier; // pct uplift, e.g. 1.15

        public SurgePricingRule(double occupancyThreshold, double demandThreshold, double multiplier) {
            this.occupancyThreshold = occupancyThreshold;
            this.demandThreshold = demandThreshold;
            this.multiplier = multiplier;
        }

        @Override public String name() { return "SurgePricing"; }
        @Override public int priority() { return 100; }
        @Override public boolean exclusive() { return false; }

        @Override public boolean applies(PricingContext ctx) {
            return ctx.occupancy >= occupancyThreshold || ctx.demandIndex >= demandThreshold;
        }

        @Override public double apply(PricingContext ctx, double currentPrice, List<String> audit, Map<String,Object> metaOut) {
            metaOut.put("occupancy", ctx.occupancy);
            metaOut.put("demandIndex", ctx.demandIndex);
            metaOut.put("multiplier", multiplier);
            return currentPrice * multiplier;
        }
    }

    /** High demand surge pricing - applies when demand index is very high regardless of occupancy. */
    public static final class HighDemandSurgeRule implements PricingRule {
        private final double demandThreshold;
        private final double multiplier;

        public HighDemandSurgeRule(double demandThreshold, double multiplier) {
            this.demandThreshold = demandThreshold;
            this.multiplier = multiplier;
        }

        @Override public String name() { return "HighDemandSurge"; }
        @Override public int priority() { return 95; } // Just below main surge pricing
        @Override public boolean exclusive() { return false; }

        @Override public boolean applies(PricingContext ctx) {
            return ctx.demandIndex >= demandThreshold;
        }

        @Override public double apply(PricingContext ctx, double currentPrice, List<String> audit, Map<String,Object> metaOut) {
            metaOut.put("demandIndex", ctx.demandIndex);
            metaOut.put("multiplier", multiplier);
            return currentPrice * multiplier;
        }
    }

    /** Early-bird discount if booking far in advance. */
    public static final class EarlyBirdDiscountRule implements PricingRule {
        private final long minDays;
        private final double discountPct; // e.g. 0.10 => 10%

        public EarlyBirdDiscountRule(long minDays, double discountPct) {
            this.minDays = minDays;
            this.discountPct = discountPct;
        }

        @Override public String name() { return "EarlyBirdDiscount"; }
        @Override public int priority() { return 80; }
        @Override public boolean exclusive() { return false; }

        @Override public boolean applies(PricingContext ctx) {
            return ctx.bookingWindowDays >= minDays;
        }

        @Override public double apply(PricingContext ctx, double currentPrice, List<String> audit, Map<String,Object> metaOut) {
            metaOut.put("bookingWindowDays", ctx.bookingWindowDays);
            metaOut.put("discountPct", discountPct);
            return currentPrice * (1 - discountPct);
        }
    }

    /** Last-minute deal if booking window is short and occupancy is low. */
    public static final class LastMinuteDealRule implements PricingRule {
        private final long maxDays;
        private final double maxOccupancy;
        private final double discountPct;

        public LastMinuteDealRule(long maxDays, double maxOccupancy, double discountPct) {
            this.maxDays = maxDays;
            this.maxOccupancy = maxOccupancy;
            this.discountPct = discountPct;
        }

        @Override public String name() { return "LastMinuteDeal"; }
        @Override public int priority() { return 70; }
        @Override public boolean exclusive() { return false; }

        @Override public boolean applies(PricingContext ctx) {
            return ctx.bookingWindowDays <= maxDays && ctx.occupancy <= maxOccupancy;
        }

        @Override public double apply(PricingContext ctx, double currentPrice, List<String> audit, Map<String,Object> metaOut) {
            metaOut.put("bookingWindowDays", ctx.bookingWindowDays);
            metaOut.put("occupancy", ctx.occupancy);
            metaOut.put("discountPct", discountPct);
            return currentPrice * (1 - discountPct);
        }
    }

    /** Loyalty segment discount (combinable). */
    public static final class LoyaltyDiscountRule implements PricingRule {
        private final Map<UserSegment, Double> discounts; // 0..1

        public LoyaltyDiscountRule(Map<UserSegment, Double> discounts) {
            this.discounts = new EnumMap<>(discounts);
        }

        @Override public String name() { return "LoyaltyDiscount"; }
        @Override public int priority() { return 60; }
        @Override public boolean exclusive() { return false; }

        @Override public boolean applies(PricingContext ctx) {
            return discounts.containsKey(ctx.segment) && discounts.get(ctx.segment) > 0.0;
        }

        @Override public double apply(PricingContext ctx, double currentPrice, List<String> audit, Map<String,Object> metaOut) {
            double pct = discounts.getOrDefault(ctx.segment, 0.0);
            metaOut.put("segment", ctx.segment.toString());
            metaOut.put("discountPct", pct);
            return currentPrice * (1 - pct);
        }
    }

    /** Align price to competitor (exclusive) if competitor is significantly lower. */
    public static final class CompetitorAlignRule implements PricingRule {
        private final double undercutPct;  // reduce to (competitor - X%)
        private final double triggerPct;   // if competitor price < current * triggerPct (e.g. 0.95)

        public CompetitorAlignRule(double undercutPct, double triggerPct) {
            this.undercutPct = undercutPct;
            this.triggerPct = triggerPct;
        }

        @Override public String name() { return "CompetitorAlign"; }
        @Override public int priority() { return 90; }
        @Override public boolean exclusive() { return true; } // when we align, we stop (policy decision)

        @Override public boolean applies(PricingContext ctx) {
            return ctx.competitorPrice != null
                    && ctx.competitorPrice < (ctx.basePrice * ctx.seasonMultiplier) * triggerPct;
        }

        @Override public double apply(PricingContext ctx, double currentPrice, List<String> audit, Map<String,Object> metaOut) {
            double target = ctx.competitorPrice * (1 - undercutPct);
            metaOut.put("competitorPrice", ctx.competitorPrice);
            metaOut.put("undercutPct", undercutPct);
            metaOut.put("triggerPct", triggerPct);
            return Math.min(currentPrice, target);
        }
    }

    /** Example seasonal multiplier rule – implement in feeds here to keep engine generic. (We apply season in base step). */
    // (Handled by seasonMultiplier in PricingContext)

    // ====== SIMPLE FORECASTERS ======

    /** Baseline forecaster: simple heuristic mapping occupancy + booking window to demand. */
    public static final class HeuristicForecaster implements DemandForecaster {
        @Override public String name() { return "HeuristicForecaster"; }

        @Override public double forecast(DemandContext ctx) {
            // Enhanced: demand grows with occupancy and closeness to check-in
            double occFactor = clamp(ctx.currentOccupancy, 0, 1);
            double timeFactor = 1.0 - clamp(ctx.bookingWindowDays / 365.0, 0, 1);
            double competitorFactor = 0.5;
            if (ctx.competitorPrice != null && ctx.competitorPrice > 0) {
                // if we are cheaper than competitor, demand increases a bit
                competitorFactor = 0.5 + 0.2;
            }
            
            // More aggressive demand calculation for higher occupancy scenarios
            double d = 0.4 * occFactor + 0.4 * timeFactor + 0.2 * competitorFactor;
            
            // Boost demand for high occupancy scenarios
            if (occFactor > 0.7) {
                d += 0.2; // Additional 20% boost for high occupancy
            }
            
            return clamp(d, 0, 1);
        }
    }

    // ====== LIVE FEEDS (in-memory) ======

    /**
     * Simple, thread-safe in-memory feeds. In production, these would be adapters wrapping
     * Kafka/Kinesis, databases, or API clients.
     */
    public static final class Feeds {
        // occupancy[hotelId] = 0..1
        public final Map<String, Double> occupancy = new ConcurrentHashMap<>();

        // baseRates[(hotelId, roomType)] = basePrice
        public final Map<Key2, Double> baseRates = new ConcurrentHashMap<>();

        // competitorPrices[(hotelId, roomType)] = competitor price
        public final Map<Key2, Double> competitorPrices = new ConcurrentHashMap<>();

        // season multipliers by (hotelId, month): default 1.0
        public final Map<Key2, Double> seasonalMultipliers = new ConcurrentHashMap<>();

        public double getOccupancy(String hotelId) {
            return occupancy.getOrDefault(hotelId, 0.7);
        }
        public double getBaseRate(String hotelId, String roomType, double fallback) {
            return baseRates.getOrDefault(new Key2(hotelId, roomType), fallback);
        }
        public Double getCompetitorPrice(String hotelId, String roomType) {
            return competitorPrices.get(new Key2(hotelId, roomType));
        }
        public double getSeasonMultiplier(String hotelId, Month month) {
            return seasonalMultipliers.getOrDefault(new Key2(hotelId, month.toString()), 1.0);
        }
    }

    public static final class Key2 {
        public final String k1;
        public final String k2;

        public Key2(String k1, String k2) {
            this.k1 = k1;
            this.k2 = k2;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key2)) return false;
            Key2 key2 = (Key2) o;
            return Objects.equals(k1, key2.k1) && Objects.equals(k2, key2.k2);
        }

        @Override public int hashCode() {
            return Objects.hash(k1, k2);
        }
    }

    // ====== SERVICE ======

    private final RuleEngine ruleEngine;
    private final DemandForecaster forecaster;
    private final Feeds feeds;

    public DynamicPricingService() {
        this.feeds = new Feeds();

        // Default seasonal multipliers example
        feeds.seasonalMultipliers.put(new Key2("H1", Month.DECEMBER.toString()), 1.30); // peak
        feeds.seasonalMultipliers.put(new Key2("H1", Month.JANUARY.toString()), 0.90);  // low

        // Default base rates (fallbacks)
        feeds.baseRates.put(new Key2("H1", "DLX"), 5000.0);
        feeds.baseRates.put(new Key2("H1", "STD"), 3000.0);

        // Default occupancy - set higher to trigger surge pricing more easily
        feeds.occupancy.put("H1", 0.82);

        // Build default ruleset
        List<PricingRule> rules = List.of(
                new CompetitorAlignRule(0.03, 0.95),
                new SurgePricingRule(0.80, 0.75, 1.20), // Lowered thresholds for more aggressive surge pricing
                new HighDemandSurgeRule(0.75, 1.15), // Additional surge for high demand scenarios
                new EarlyBirdDiscountRule(60, 0.10),
                new LastMinuteDealRule(3, 0.50, 0.20),
                new LoyaltyDiscountRule(Map.of(
                        UserSegment.SILVER, 0.03,
                        UserSegment.GOLD, 0.07,
                        UserSegment.PLATINUM, 0.12,
                        UserSegment.CORPORATE, 0.08,
                        UserSegment.LOYAL, 0.05 // Added LOYAL segment with 5% discount
                ))
        );
        this.ruleEngine = new RuleEngine(rules);
        this.forecaster = new HeuristicForecaster();
    }

    public void start(int port) throws IOException {
        LOG.info("Starting DynamicPricingService on port " + port);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", exchange -> respondJson(exchange, 200, Map.of("status", "UP")));
        server.createContext("/price", new PriceHandler());
        server.createContext("/feeds/occupancy", new OccupancyFeedHandler());
        server.createContext("/feeds/competitor", new CompetitorFeedHandler());
        server.createContext("/feeds/baseRate", new BaseRateFeedHandler());
        server.setExecutor(Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()));
        server.start();
        LOG.info("DynamicPricingService started on port " + port);
    }

    // ====== HTTP HANDLERS ======

    /** GET /price?hotelId=H1&roomType=DLX&checkIn=2025-12-20&nights=3&userSegment=GOLD&base=5000 */
    private class PriceHandler implements HttpHandler {
        @Override public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    respondJson(exchange, 405, Map.of("error", "Method Not Allowed"));
                    return;
                }

                Map<String, String> q = splitQuery(exchange.getRequestURI());

                String hotelId = require(q, "hotelId");
                String roomType = require(q, "roomType");
                LocalDate checkIn = LocalDate.parse(require(q, "checkIn"), DateTimeFormatter.ISO_LOCAL_DATE);
                int nights = parseIntOr(q.get("nights"), 1);
                UserSegment segment = parseEnumOr(q.get("userSegment"), UserSegment.class, UserSegment.GUEST);
                double inputBase = parseDoubleOr(q.get("base"), Double.NaN);

                // Pull from feeds
                double occupancy = feeds.getOccupancy(hotelId);
                double baseRate = Double.isNaN(inputBase) ? feeds.getBaseRate(hotelId, roomType, 4000.0) : inputBase;
                Double competitor = feeds.getCompetitorPrice(hotelId, roomType);
                double seasonMult = feeds.getSeasonMultiplier(hotelId, checkIn.getMonth());

                long bookingWindowDays = Period.between(LocalDate.now(), checkIn).getDays();
                bookingWindowDays = Math.max(0, bookingWindowDays);

                // Forecast demand
                DemandContext dctx = new DemandContext(hotelId, roomType, checkIn, bookingWindowDays, occupancy, competitor);
                double demandIndex = forecaster.forecast(dctx);

                // Debug logging
                LOG.info(String.format("Pricing request - hotelId: %s, roomType: %s, segment: %s, occupancy: %.2f, demandIndex: %.2f, bookingWindowDays: %d", 
                    hotelId, roomType, segment, occupancy, demandIndex, bookingWindowDays));

                // Compose context
                PricingContext pctx = new PricingContext(
                        hotelId, roomType, checkIn, nights, segment,
                        baseRate, occupancy, competitor, seasonMult, bookingWindowDays, demandIndex
                );

                PriceComputation res = ruleEngine.execute(pctx, forecaster);
                Map<String, Object> json = res.toJson();
                json.put("engineForecaster", forecaster.name());
                json.put("timestamp", Instant.now().toString());
                respondJson(exchange, 200, json);

            } catch (IllegalArgumentException e) {
                LOG.log(Level.WARNING, "Bad request: " + e.getMessage());
                respondJson(exchange, 400, Map.of("error", e.getMessage()));
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Internal error", e);
                respondJson(exchange, 500, Map.of("error", "Internal Server Error"));
            }
        }
    }

    /** POST /feeds/occupancy?hotelId=H1&value=0.92 */
    private class OccupancyFeedHandler implements HttpHandler {
        @Override public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    respondJson(exchange, 405, Map.of("error", "Method Not Allowed"));
                    return;
                }
                Map<String, String> q = splitQuery(exchange.getRequestURI());
                String hotelId = require(q, "hotelId");
                double value = parseDouble(require(q, "value"));
                if (value < 0 || value > 1) throw new IllegalArgumentException("occupancy must be in [0,1]");
                feeds.occupancy.put(hotelId, value);
                respondJson(exchange, 200, Map.of("ok", true, "hotelId", hotelId, "occupancy", value));
            } catch (IllegalArgumentException e) {
                respondJson(exchange, 400, Map.of("error", e.getMessage()));
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Internal error", e);
                respondJson(exchange, 500, Map.of("error", "Internal Server Error"));
            }
        }
    }

    /** POST /feeds/competitor?hotelId=H1&roomType=DLX&value=4800 */
    private class CompetitorFeedHandler implements HttpHandler {
        @Override public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    respondJson(exchange, 405, Map.of("error", "Method Not Allowed"));
                    return;
                }
                Map<String, String> q = splitQuery(exchange.getRequestURI());
                String hotelId = require(q, "hotelId");
                String roomType = require(q, "roomType");
                double value = parseDouble(require(q, "value"));
                feeds.competitorPrices.put(new Key2(hotelId, roomType), value);
                respondJson(exchange, 200, Map.of("ok", true, "hotelId", hotelId, "roomType", roomType, "competitorPrice", value));
            } catch (IllegalArgumentException e) {
                respondJson(exchange, 400, Map.of("error", e.getMessage()));
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Internal error", e);
                respondJson(exchange, 500, Map.of("error", "Internal Server Error"));
            }
        }
    }

    /** POST /feeds/baseRate?hotelId=H1&roomType=DLX&value=4500 */
    private class BaseRateFeedHandler implements HttpHandler {
        @Override public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    respondJson(exchange, 405, Map.of("error", "Method Not Allowed"));
                    return;
                }
                Map<String, String> q = splitQuery(exchange.getRequestURI());
                String hotelId = require(q, "hotelId");
                String roomType = require(q, "roomType");
                double value = parseDouble(require(q, "value"));
                feeds.baseRates.put(new Key2(hotelId, roomType), value);
                respondJson(exchange, 200, Map.of("ok", true, "hotelId", hotelId, "roomType", roomType, "baseRate", value));
            } catch (IllegalArgumentException e) {
                respondJson(exchange, 400, Map.of("error", e.getMessage()));
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Internal error", e);
                respondJson(exchange, 500, Map.of("error", "Internal Server Error"));
            }
        }
    }

    // ====== UTIL ======

    private static void respondJson(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
        byte[] bytes = toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> splitQuery(URI uri) {
        Map<String, String> map = new HashMap<>();
        String q = uri.getRawQuery();
        if (q == null || q.isEmpty()) return map;
        for (String p : q.split("&")) {
            int idx = p.indexOf('=');
            if (idx == -1) {
                map.put(decode(p), "");
            } else {
                map.put(decode(p.substring(0, idx)), decode(p.substring(idx + 1)));
            }
        }
        return map;
    }

    private static String decode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static String require(Map<String, String> map, String key) {
        String v = map.get(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing required param: " + key);
        return v;
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid double: " + s);
        }
    }

    private static int parseIntOr(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static <E extends Enum<E>> E parseEnumOr(String s, Class<E> clazz, E def) {
        if (s == null) return def;
        try {
            return Enum.valueOf(clazz, s.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return def;
        }
    }

    private static double parseDoubleOr(String s, double def) {
        try {
            return s == null ? def : Double.parseDouble(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
        // or Math.round(v * 100.0) / 100.0;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /** Very small JSON serializer for Maps/Lists/scalars. */
    @SuppressWarnings("unchecked")
    public static String toJson(Object o) {
        if (o == null) return "null";
        if (o instanceof String) return "\"" + escape((String) o) + "\"";
        if (o instanceof Number || o instanceof Boolean) return o.toString();
        if (o instanceof Map) {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append(toJson(e.getKey().toString()));
                sb.append(":");
                sb.append(toJson(e.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
        if (o instanceof Collection) {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            boolean first = true;
            for (Object v : (Collection<?>) o) {
                if (!first) sb.append(",");
                first = false;
                sb.append(toJson(v));
            }
            sb.append("]");
            return sb.toString();
        }
        // Fallback – reflectively toString, but safer to wrap
        return "\"" + escape(o.toString()) + "\"";
    }

    private static String escape(String s) {
        return s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b","\\b")
                .replace("\f","\\f")
                .replace("\n","\\n")
                .replace("\r","\\r")
                .replace("\t","\\t");
    }

    // ====== UNIT TESTS ======
    private static void runTests() {
        LOG.info("Running unit tests...");
        DynamicPricingService svc = new DynamicPricingService();

        // Test 1: Early bird discount applies
        {
            LocalDate checkIn = LocalDate.now().plusDays(100);
            svc.feeds.occupancy.put("H1", 0.5);
            DemandContext dctx = new DemandContext("H1", "DLX", checkIn, 100, 0.5, null);
            double demandIndex = svc.forecaster.forecast(dctx);

            PricingContext ctx = new PricingContext("H1", "DLX", checkIn, 2, UserSegment.GUEST,
                    svc.feeds.getBaseRate("H1", "DLX", 5000), 0.5, null,
                    svc.feeds.getSeasonMultiplier("H1", checkIn.getMonth()),
                    100, demandIndex);

            PriceComputation res = svc.ruleEngine.execute(ctx, svc.forecaster);
            assert res.finalPrice < ctx.basePrice * ctx.seasonMultiplier : "Early bird should discount the price";
            assert res.appliedRules.stream().anyMatch(r -> r.name.equals("EarlyBirdDiscount")) : "Rule not applied";
        }

        // Test 2: Last minute + low occupancy => last minute deal
        {
            LocalDate checkIn = LocalDate.now().plusDays(1);
            svc.feeds.occupancy.put("H1", 0.3);
            DemandContext dctx = new DemandContext("H1", "DLX", checkIn, 1, 0.3, null);
            double demandIndex = svc.forecaster.forecast(dctx);

            PricingContext ctx = new PricingContext("H1", "DLX", checkIn, 1, UserSegment.GUEST,
                    5000, 0.3, null,
                    svc.feeds.getSeasonMultiplier("H1", checkIn.getMonth()),
                    1, demandIndex);

            PriceComputation res = svc.ruleEngine.execute(ctx, svc.forecaster);
            assert res.appliedRules.stream().anyMatch(r -> r.name.equals("LastMinuteDeal")) : "LastMinuteDeal not applied";
        }

        // Test 3: Surge with high occupancy
        {
            LocalDate checkIn = LocalDate.now().plusDays(2);
            svc.feeds.occupancy.put("H1", 0.95);
            DemandContext dctx = new DemandContext("H1", "DLX", checkIn, 2, 0.95, null);
            double demandIndex = svc.forecaster.forecast(dctx);

            PricingContext ctx = new PricingContext("H1", "DLX", checkIn, 1, UserSegment.GUEST,
                    5000, 0.95, null,
                    svc.feeds.getSeasonMultiplier("H1", checkIn.getMonth()),
                    2, demandIndex);

            PriceComputation res = svc.ruleEngine.execute(ctx, svc.forecaster);
            assert res.appliedRules.stream().anyMatch(r -> r.name.equals("SurgePricing")) : "SurgePricing not applied";
            assert res.finalPrice > ctx.basePrice : "Surge should increase price";
        }

        // Test 4: Competitor align exclusive
        {
            LocalDate checkIn = LocalDate.now().plusDays(10);
            svc.feeds.occupancy.put("H1", 0.6);
            svc.feeds.competitorPrices.put(new Key2("H1", "DLX"), 4000.0);
            DemandContext dctx = new DemandContext("H1", "DLX", checkIn, 10, 0.6, 4000.0);
            double demandIndex = svc.forecaster.forecast(dctx);

            PricingContext ctx = new PricingContext("H1", "DLX", checkIn, 1, UserSegment.GUEST,
                    5000, 0.6, 4000.0,
                    svc.feeds.getSeasonMultiplier("H1", checkIn.getMonth()),
                    10, demandIndex);

            PriceComputation res = svc.ruleEngine.execute(ctx, svc.forecaster);
            boolean competitorApplied = res.appliedRules.stream().anyMatch(r -> r.name.equals("CompetitorAlign"));
            assert competitorApplied : "CompetitorAlign should apply";
            assert res.appliedRules.get(res.appliedRules.size() - 1).exclusive : "Should be exclusive";
        }

        // Test 5: Loyalty stack
        {
            LocalDate checkIn = LocalDate.now().plusDays(30);
            svc.feeds.occupancy.put("H1", 0.5);
            DemandContext dctx = new DemandContext("H1", "DLX", checkIn, 30, 0.5, null);
            double demandIndex = svc.forecaster.forecast(dctx);

            PricingContext ctx = new PricingContext("H1", "DLX", checkIn, 1, UserSegment.PLATINUM,
                    5000, 0.5, null,
                    svc.feeds.getSeasonMultiplier("H1", checkIn.getMonth()),
                    30, demandIndex);

            PriceComputation res = svc.ruleEngine.execute(ctx, svc.forecaster);
            boolean loyaltyApplied = res.appliedRules.stream().anyMatch(r -> r.name.equals("LoyaltyDiscount"));
            assert loyaltyApplied : "LoyaltyDiscount should apply for PLATINUM";
        }

        // Test 6: LOYAL segment discount
        {
            LocalDate checkIn = LocalDate.now().plusDays(30);
            svc.feeds.occupancy.put("H1", 0.5);
            DemandContext dctx = new DemandContext("H1", "DLX", checkIn, 30, 0.5, null);
            double demandIndex = svc.forecaster.forecast(dctx);

            PricingContext ctx = new PricingContext("H1", "DLX", checkIn, 1, UserSegment.LOYAL,
                    5000, 0.5, null,
                    svc.feeds.getSeasonMultiplier("H1", checkIn.getMonth()),
                    30, demandIndex);

            PriceComputation res = svc.ruleEngine.execute(ctx, svc.forecaster);
            boolean loyaltyApplied = res.appliedRules.stream().anyMatch(r -> r.name.equals("LoyaltyDiscount"));
            assert loyaltyApplied : "LoyaltyDiscount should apply for LOYAL";
        }

        LOG.info("All tests passed!");
    }

    // ====== HOW TO EXTEND ======
    /*
     * Adding a new pricing rule:
     * 1. Implement PricingRule (name, priority, exclusive, applies, apply).
     * 2. Register it in the list passed to RuleEngine in the constructor (order doesn't matter; we sort by priority).
     *
     * Adding a new forecaster:
     * 1. Implement DemandForecaster.
     * 2. Instantiate & inject into DynamicPricingService instead of HeuristicForecaster.
     *
     * Swapping live feeds:
     * 1. Replace Feeds with your adapter over your streaming / DB layer.
     *
     * Testing:
     * - Keep all logic in pure classes (as we did) so you can unit test without HTTP.
     * - We embedded a few assert-based tests in runTests(). Switch to JUnit in multi-file setups.
     */

    // ====== MAIN ======
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "test".equalsIgnoreCase(args[0])) {
            runTests();
            return;
        }
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try { port = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
        }
        new DynamicPricingService().start(port);
    }
}

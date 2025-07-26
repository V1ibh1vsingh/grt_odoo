#!/usr/bin/env python3
"""
Simple test script to verify dynamic pricing logic
"""
import requests
import json

def test_pricing():
    base_url = "http://localhost:8080"
    
    # Test 1: Original request that should now trigger surge pricing
    print("=== Test 1: Original request with LOYAL segment ===")
    response = requests.get(f"{base_url}/price", params={
        "hotelId": "123",
        "roomType": "deluxe", 
        "checkIn": "2025-09-27",
        "userSegment": "loyal"
    })
    
    if response.status_code == 200:
        result = response.json()
        print(f"Status: {response.status_code}")
        print(f"Base Price: {result.get('basePrice')}")
        print(f"Final Price: {result.get('finalPrice')}")
        print(f"Demand Index: {result.get('demandIndex')}")
        print(f"Applied Rules: {[r['name'] for r in result.get('appliedRules', [])]}")
        print(f"Audit Trail: {result.get('auditTrail', [])}")
        
        # Check if price increased
        if result.get('finalPrice', 0) > result.get('basePrice', 0):
            print("✅ SUCCESS: Price was increased due to surge pricing!")
        else:
            print("❌ FAILED: Price was not increased")
    else:
        print(f"❌ Request failed: {response.status_code} - {response.text}")
    
    print("\n" + "="*50 + "\n")
    
    # Test 2: Test with high occupancy to trigger surge
    print("=== Test 2: High occupancy scenario ===")
    
    # First set high occupancy
    occupancy_response = requests.post(f"{base_url}/feeds/occupancy", params={
        "hotelId": "123",
        "value": "0.90"
    })
    print(f"Set occupancy: {occupancy_response.status_code}")
    
    # Then test pricing
    response = requests.get(f"{base_url}/price", params={
        "hotelId": "123",
        "roomType": "deluxe", 
        "checkIn": "2025-09-27",
        "userSegment": "loyal"
    })
    
    if response.status_code == 200:
        result = response.json()
        print(f"Status: {response.status_code}")
        print(f"Base Price: {result.get('basePrice')}")
        print(f"Final Price: {result.get('finalPrice')}")
        print(f"Demand Index: {result.get('demandIndex')}")
        print(f"Applied Rules: {[r['name'] for r in result.get('appliedRules', [])]}")
        
        if result.get('finalPrice', 0) > result.get('basePrice', 0):
            print("✅ SUCCESS: Price was increased due to high occupancy!")
        else:
            print("❌ FAILED: Price was not increased despite high occupancy")
    else:
        print(f"❌ Request failed: {response.status_code} - {response.text}")

if __name__ == "__main__":
    test_pricing() 
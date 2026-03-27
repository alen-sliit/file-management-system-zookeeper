package com.example.zookeeper.test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects and records performance metrics during tests.
 * Saves results to CSV files for analysis and reporting.
 */
public class PerformanceMetrics {
    
    private List<TestResult> results = new ArrayList<>();
    private String testName;
    private long testStartTime;
    
    public PerformanceMetrics(String testName) {
        this.testName = testName;
        this.testStartTime = System.currentTimeMillis();
        
        // Create test-results directory if it doesn't exist
        File dir = new File("test-results");
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }
    
    /**
     * Record a single test result.
     */
    public void recordResult(String scenario, long durationMs, boolean success, String details) {
        TestResult result = new TestResult();
        result.scenario = scenario;
        result.durationMs = durationMs;
        result.success = success;
        result.details = details;
        result.timestamp = System.currentTimeMillis();
        results.add(result);
        
        System.out.printf("[METRIC] %s: %d ms - %s%n", 
                          scenario, durationMs, success ? "SUCCESS" : "FAILED");
        if (details != null && !details.isEmpty()) {
            System.out.printf("        Details: %s%n", details);
        }
    }
    
    /**
     * Print summary of all test results.
     */
    public void printSummary() {
        long totalDuration = System.currentTimeMillis() - testStartTime;
        
        System.out.println("\n========================================");
        System.out.println("Performance Test Summary: " + testName);
        System.out.println("========================================");
        System.out.printf("Total test duration: %d ms%n", totalDuration);
        System.out.printf("Total operations: %d%n", results.size());
        
        long successfulOps = results.stream().filter(r -> r.success).count();
        System.out.printf("Successful: %d%n", successfulOps);
        System.out.printf("Failed: %d%n", results.size() - successfulOps);
        
        double avgDuration = results.stream()
                .mapToLong(r -> r.durationMs)
                .average()
                .orElse(0);
        System.out.printf("Average operation time: %.2f ms%n", avgDuration);
        
        long maxDuration = results.stream()
                .mapToLong(r -> r.durationMs)
                .max()
                .orElse(0);
        System.out.printf("Max operation time: %d ms%n", maxDuration);
        
        long minDuration = results.stream()
                .mapToLong(r -> r.durationMs)
                .min()
                .orElse(0);
        System.out.printf("Min operation time: %d ms%n", minDuration);
        System.out.println("========================================\n");
    }
    
    /**
     * Save results to CSV file.
     */
    public void saveToFile() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = "test-results/" + testName + "_" + timestamp + ".csv";
        
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            writer.println("Scenario,Duration(ms),Success,Details,Timestamp");
            for (TestResult r : results) {
                writer.printf("%s,%d,%s,%s,%d%n",
                    r.scenario, r.durationMs, r.success, 
                    r.details != null ? r.details.replace(",", ";") : "", 
                    r.timestamp);
            }
            System.out.println("Results saved to: " + filename);
        } catch (IOException e) {
            System.err.println("Failed to save results: " + e.getMessage());
        }
    }
    
    /**
     * Get all results for custom processing.
     */
    public List<TestResult> getResults() {
        return results;
    }
    
    /**
     * Inner class representing a single test result.
     */
    public static class TestResult {
        public String scenario;
        public long durationMs;
        public boolean success;
        public String details;
        public long timestamp;
    }
}
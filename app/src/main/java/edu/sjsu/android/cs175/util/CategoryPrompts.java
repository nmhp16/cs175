package edu.sjsu.android.cs175.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import edu.sjsu.android.cs175.data.Category;

/**
 * Suggested questions per document category. Shown as chips above the chat
 * input on an empty chat.
 */
public final class CategoryPrompts {

    private CategoryPrompts() {}

    public static List<String> suggestionsFor(Category category) {
        switch (category) {
            case LEASE:
                return Arrays.asList(
                        "When does my lease end?",
                        "What's the monthly rent?",
                        "Any early termination fees?",
                        "Who's responsible for repairs?",
                        "Is there a late fee clause?");
            case MEDICAL:
                return Arrays.asList(
                        "What are the key findings?",
                        "Is anything flagged abnormal?",
                        "What follow-up is recommended?",
                        "Summarize in plain English");
            case WARRANTY:
                return Arrays.asList(
                        "When does coverage expire?",
                        "What's covered?",
                        "How do I file a claim?",
                        "What voids the warranty?");
            case INSURANCE:
                return Arrays.asList(
                        "What's my deductible?",
                        "What's excluded?",
                        "How do I file a claim?",
                        "When does coverage renew?");
            case TAX:
                return Arrays.asList(
                        "What's my total liability?",
                        "What deductions are listed?",
                        "When is this due?");
            case BILL:
                return Arrays.asList(
                        "What's the total due?",
                        "When is the due date?",
                        "What are the late fees?",
                        "Break down the charges");
            case CONTRACT:
                return Arrays.asList(
                        "What are my obligations?",
                        "What are the key dates?",
                        "How do I terminate this?",
                        "Any penalties?");
            case OTHER:
            default:
                return Arrays.asList(
                        "Summarize this document",
                        "What are the key dates?",
                        "What are the key amounts?",
                        "What should I pay attention to?");
        }
    }
}

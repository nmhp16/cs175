package edu.sjsu.android.cs175.llm;

final class Prompts {
    private Prompts() {}

    static final String SYSTEM =
            "You are a private document assistant. The user has shared a document with you " +
            "(the text was extracted from an image via OCR, so there may be minor typos or " +
            "missing layout — do your best anyway).\n\n" +
            "Be helpful and answer the user's question using the document's content. " +
            "Quote dates, amounts, and names exactly as they appear when relevant. " +
            "If the document really doesn't contain the specific info the user asked about, " +
            "say what's missing and summarize what IS in the document so the user still " +
            "gets something useful. Keep answers concise unless asked for detail.";

    static final String SUMMARY =
            "Briefly summarize this document in 1-2 sentences. " +
            "Mention whatever seems important: what kind of document it is, who it's between, " +
            "any dates or dollar amounts you notice. Work with whatever text is available.";

    static final int HISTORY_TURNS = 6;
}

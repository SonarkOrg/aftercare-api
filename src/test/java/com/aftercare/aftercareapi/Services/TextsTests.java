package com.aftercare.aftercareapi.Services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextsTests {

    @Test
    void shortBodyIsUntouched() {
        assertEquals("hello", Texts.preview("hello"));
        assertEquals("x".repeat(140), Texts.preview("x".repeat(140)));
    }

    @Test
    void longBodyIsCutAt140WithEllipsis() {
        assertEquals("x".repeat(140) + "…", Texts.preview("x".repeat(141)));
    }

    @Test
    void doesNotSplitASurrogatePairAtTheBoundary() {
        String emoji = "😀"; // 2 chars; high surrogate at index 139
        String body = "x".repeat(139) + emoji + "tail";
        String preview = Texts.preview(body);
        assertEquals("x".repeat(139) + "…", preview);
        assertFalse(Character.isHighSurrogate(preview.charAt(preview.length() - 2)));
    }

    @Test
    void keepsAPairThatEndsExactlyAtTheBoundary() {
        String body = "x".repeat(138) + "😀" + "tail";
        assertEquals("x".repeat(138) + "😀…", Texts.preview(body));
    }
}

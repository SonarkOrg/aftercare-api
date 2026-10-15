package com.aftercare.aftercareapi.Services;

import java.util.Optional;

/** Decides a file's type from its first bytes only. The client Content-Type and the extension are never trusted. */
public final class FileTypeSniffer {

    private FileTypeSniffer() {
    }

    public static Optional<String> sniff(byte[] head) {
        if (head == null) {
            return Optional.empty();
        }
        if (startsWith(head, new int[]{'%', 'P', 'D', 'F', '-'})) {
            return Optional.of("application/pdf");
        }
        if (startsWith(head, new int[]{0xFF, 0xD8, 0xFF})) {
            return Optional.of("image/jpeg");
        }
        if (startsWith(head, new int[]{0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})) {
            return Optional.of("image/png");
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] data, int[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}

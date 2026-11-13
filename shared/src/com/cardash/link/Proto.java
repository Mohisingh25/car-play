package com.cardash.link;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Constants and small helpers shared by the display app and the phone app. */
public final class Proto {
    private Proto() {}

    public static final int VERSION = 1;

    /** Bluetooth serial (RFCOMM) service id. The phone listens on it, the display connects to it. */
    public static final UUID SPP_UUID = UUID.fromString("7f3b1c52-4e9a-4d1b-9c6e-2a8d5f0b61c4");
    public static final String SDP_NAME = "CarDash";

    /** TCP port used by the optional Wi-Fi backup link. */
    public static final int TCP_PORT = 38472;

    /** Proof that the display knows the pairing code, without sending the code itself. */
    public static String hmacHex(String code, String nonce) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(code.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(nonce.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Compare two strings without stopping at the first difference. */
    public static boolean sameText(String a, String b) {
        if (a == null || b == null || a.isEmpty() || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }
}

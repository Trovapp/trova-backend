package com.trova.backend.shipandping;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class ProfileUpload {
    final byte[] body;
    private final Path receipt;
    private final String digest;
    private boolean accepted;

    ProfileUpload(byte[] body, String key, URI endpoint, Path receipt) {
        this.body = body;
        this.receipt = receipt.toAbsolutePath();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(endpoint.toASCIIString().getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            digest = HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    boolean pending() {
        if (!accepted) {
            try {
                accepted = Files.size(receipt) == digest.length() && digest.equals(Files.readString(receipt));
            } catch (IOException | SecurityException ignored) {
                // First connection, or no writable persistent state on this host.
            }
        }
        return !accepted;
    }

    void acknowledge() throws IOException {
        accepted = true;
        Files.createDirectories(receipt.getParent());
        Path temp = Files.createTempFile(receipt.getParent(), "profile-", ".tmp");
        try {
            Files.writeString(temp, digest);
            Files.move(temp, receipt, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}

package com.wellsetups.wellviptime.voucher;

import com.wellsetups.wellviptime.vip.DomainFailure;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class VoucherSigner {

    private final byte[] key;

    private final String network;

    public VoucherSigner(byte[] key, String network) {
        if (key.length != 32) {
            throw new IllegalArgumentException("Signing key must contain exactly 32 bytes");
        }
        this.key = key.clone();
        this.network = network;
    }

    public static VoucherSigner load(Path path, String network) throws IOException {
        if (!Files.exists(path)) {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            try {
                Files.write(path, key, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException ignored) {
                /* Another initializer won creation. Read its key below. */
            }
        }
        if (Files.size(path) != 32) {
            throw new IOException(
                    "Signing key file must contain exactly 32 bytes; restore the original key.");
        }
        return new VoucherSigner(Files.readAllBytes(path), network);
    }

    public String fingerprint() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public byte[] encode(Voucher voucher) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(voucher.schema());
                out.writeUTF(network);
                out.writeUTF(voucher.id().toString());
                out.writeUTF(voucher.type());
                out.writeLong(voucher.durationMs());
                out.writeLong(voucher.createdAt());
                out.writeUTF(voucher.issuer().toString());
                out.writeBoolean(voucher.owner() != null);
                if (voucher.owner() != null) {
                    out.writeUTF(voucher.owner().toString());
                }
                out.writeBoolean(voucher.historicalCredit());
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public Voucher verify(byte[] payload, byte[] signature) {
        if (payload == null
                || payload.length > 512
                || signature == null
                || signature.length != 32
                || !MessageDigest.isEqual(sign(payload), signature)) {
            throw new DomainFailure("error.voucher-invalid");
        }
        try (var in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int schema = in.readInt();
            if (schema != 1) {
                throw new DomainFailure("error.voucher-version");
            }
            if (!network.equals(in.readUTF())) {
                throw new DomainFailure("error.voucher-invalid");
            }
            var voucher =
                    new Voucher(
                            UUID.fromString(in.readUTF()),
                            schema,
                            in.readUTF(),
                            in.readLong(),
                            in.readLong(),
                            UUID.fromString(in.readUTF()),
                            in.readBoolean() ? UUID.fromString(in.readUTF()) : null,
                            in.readBoolean());
            if (in.available() != 0) {
                throw new DomainFailure("error.voucher-invalid");
            }
            return voucher;
        } catch (IOException | IllegalArgumentException e) {
            throw new DomainFailure("error.voucher-invalid");
        }
    }
}

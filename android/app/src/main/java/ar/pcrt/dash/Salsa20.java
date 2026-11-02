package ar.pcrt.dash;

/** Salsa20/20 (clave de 32 bytes, nonce de 8). GT7 cifra cada paquete de telemetria con esto. */
final class Salsa20 {
    private Salsa20() {}

    private static int rotl(int v, int c) {
        return (v << c) | (v >>> (32 - c));
    }

    private static int le(byte[] b, int off) {
        return (b[off] & 0xff) | (b[off + 1] & 0xff) << 8 | (b[off + 2] & 0xff) << 16 | (b[off + 3] & 0xff) << 24;
    }

    /** Cifrar y descifrar es lo mismo (XOR con el keystream). */
    static byte[] xor(byte[] key, byte[] nonce, byte[] in) {
        int[] st = new int[16];
        st[0] = 0x61707865; st[5] = 0x3320646e; st[10] = 0x79622d32; st[15] = 0x6b206574;
        for (int i = 0; i < 4; i++) {
            st[1 + i] = le(key, 4 * i);
            st[11 + i] = le(key, 16 + 4 * i);
        }
        st[6] = le(nonce, 0);
        st[7] = le(nonce, 4);
        byte[] out = new byte[in.length];
        int[] x = new int[16];
        byte[] block = new byte[64];
        for (int pos = 0; pos < in.length; pos += 64) {
            System.arraycopy(st, 0, x, 0, 16);
            for (int r = 0; r < 10; r++) {
                x[4] ^= rotl(x[0] + x[12], 7);  x[8] ^= rotl(x[4] + x[0], 9);   x[12] ^= rotl(x[8] + x[4], 13);  x[0] ^= rotl(x[12] + x[8], 18);
                x[9] ^= rotl(x[5] + x[1], 7);   x[13] ^= rotl(x[9] + x[5], 9);  x[1] ^= rotl(x[13] + x[9], 13);  x[5] ^= rotl(x[1] + x[13], 18);
                x[14] ^= rotl(x[10] + x[6], 7); x[2] ^= rotl(x[14] + x[10], 9); x[6] ^= rotl(x[2] + x[14], 13);  x[10] ^= rotl(x[6] + x[2], 18);
                x[3] ^= rotl(x[15] + x[11], 7); x[7] ^= rotl(x[3] + x[15], 9);  x[11] ^= rotl(x[7] + x[3], 13);  x[15] ^= rotl(x[11] + x[7], 18);
                x[1] ^= rotl(x[0] + x[3], 7);   x[2] ^= rotl(x[1] + x[0], 9);   x[3] ^= rotl(x[2] + x[1], 13);   x[0] ^= rotl(x[3] + x[2], 18);
                x[6] ^= rotl(x[5] + x[4], 7);   x[7] ^= rotl(x[6] + x[5], 9);   x[4] ^= rotl(x[7] + x[6], 13);   x[5] ^= rotl(x[4] + x[7], 18);
                x[11] ^= rotl(x[10] + x[9], 7); x[8] ^= rotl(x[11] + x[10], 9); x[9] ^= rotl(x[8] + x[11], 13);  x[10] ^= rotl(x[9] + x[8], 18);
                x[12] ^= rotl(x[15] + x[14], 7); x[13] ^= rotl(x[12] + x[15], 9); x[14] ^= rotl(x[13] + x[12], 13); x[15] ^= rotl(x[14] + x[13], 18);
            }
            for (int i = 0; i < 16; i++) {
                int v = x[i] + st[i];
                block[4 * i] = (byte) v;
                block[4 * i + 1] = (byte) (v >>> 8);
                block[4 * i + 2] = (byte) (v >>> 16);
                block[4 * i + 3] = (byte) (v >>> 24);
            }
            int n = Math.min(64, in.length - pos);
            for (int j = 0; j < n; j++) out[pos + j] = (byte) (in[pos + j] ^ block[j]);
            if (++st[8] == 0) st[9]++;
        }
        return out;
    }
}

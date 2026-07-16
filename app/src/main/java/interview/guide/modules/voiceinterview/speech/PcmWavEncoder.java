package interview.guide.modules.voiceinterview.speech;

public final class PcmWavEncoder {

  private static final int HEADER_SIZE = 44;
  private static final int CHANNELS = 1;
  private static final int BITS_PER_SAMPLE = 16;

  private PcmWavEncoder() {
  }

  public static byte[] encode(byte[] pcmData, int sampleRate) {
    int dataSize = pcmData.length;
    int byteRate = sampleRate * CHANNELS * BITS_PER_SAMPLE / 8;
    short blockAlign = (short) (CHANNELS * BITS_PER_SAMPLE / 8);
    byte[] wav = new byte[dataSize + HEADER_SIZE];
    int offset = 0;
    offset = writeAscii(wav, offset, "RIFF");
    writeIntLE(wav, offset, dataSize + 36);
    offset += 4;
    offset = writeAscii(wav, offset, "WAVE");
    offset = writeAscii(wav, offset, "fmt ");
    writeIntLE(wav, offset, 16);
    offset += 4;
    writeShortLE(wav, offset, (short) 1);
    offset += 2;
    writeShortLE(wav, offset, (short) CHANNELS);
    offset += 2;
    writeIntLE(wav, offset, sampleRate);
    offset += 4;
    writeIntLE(wav, offset, byteRate);
    offset += 4;
    writeShortLE(wav, offset, blockAlign);
    offset += 2;
    writeShortLE(wav, offset, (short) BITS_PER_SAMPLE);
    offset += 2;
    offset = writeAscii(wav, offset, "data");
    writeIntLE(wav, offset, dataSize);
    System.arraycopy(pcmData, 0, wav, HEADER_SIZE, dataSize);
    return wav;
  }

  private static int writeAscii(byte[] target, int offset, String value) {
    for (int index = 0; index < value.length(); index++) {
      target[offset++] = (byte) value.charAt(index);
    }
    return offset;
  }

  private static void writeIntLE(byte[] target, int offset, int value) {
    target[offset] = (byte) value;
    target[offset + 1] = (byte) (value >>> 8);
    target[offset + 2] = (byte) (value >>> 16);
    target[offset + 3] = (byte) (value >>> 24);
  }

  private static void writeShortLE(byte[] target, int offset, short value) {
    target[offset] = (byte) value;
    target[offset + 1] = (byte) (value >>> 8);
  }
}

package com.gimle.fabric.transport;

import com.gimle.core.exception.GimleCodecException;
import com.gimle.fabric.trace.TraceContext;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;

/**
 * Encodes/decodes a {@link FabricFrame} as a 4-byte big-endian length prefix followed by a one-byte
 * frame-type tag and the frame's fields, each {@code byte[]} field itself length-prefixed. This
 * diverges from {@code ControlMessageCodec}'s line-oriented text framing for the first genuinely
 * new reason in the codebase (Phase 4 §7): {@code serializedArgs}/{@code serializedReturn}/{@code
 * serializedThrowable} are arbitrary {@code ObjectOutputStream} bytes that can contain any byte
 * value including newlines, so a text codec's escaping trick doesn't apply -- length-prefixing is
 * the standard, minimal fix.
 */
public final class FabricCodec {

  private static final byte TAG_INVOKE_REQUEST = 0;
  private static final byte TAG_INVOKE_RESPONSE = 1;
  private static final byte TAG_INVOKE_ERROR = 2;

  /**
   * Generous upper bound for any single length-prefixed frame or byte-array field this codec ever
   * produces -- far below what a corrupted or adversarial peer could otherwise force this reader to
   * allocate.
   */
  private static final int MAX_FRAME_LENGTH = 64 * 1024 * 1024;

  private FabricCodec() {}

  private static void checkFrameLength(int length) {
    if (length < 0) {
      throw GimleCodecException.invalidFrameLength(length);
    }
    if (length > MAX_FRAME_LENGTH) {
      throw GimleCodecException.frameTooLarge(length, MAX_FRAME_LENGTH);
    }
  }

  public static void write(OutputStream out, FabricFrame frame) throws IOException {
    byte[] body = encodeBody(frame);
    DataOutputStream data = new DataOutputStream(out);
    data.writeInt(body.length);
    data.write(body);
    data.flush();
  }

  /**
   * Returns {@code null} at a clean end-of-stream (no frame started), matching {@code
   * InputStream#read}'s own EOF convention rather than throwing for an expected connection close.
   */
  public static FabricFrame read(InputStream in) throws IOException {
    DataInputStream data = new DataInputStream(in);
    int length;
    try {
      length = data.readInt();
    } catch (EOFException e) {
      return null;
    }
    checkFrameLength(length);
    byte[] body = new byte[length];
    data.readFully(body);
    return decodeBody(body);
  }

  private static byte[] encodeBody(FabricFrame frame) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    try {
      switch (frame) {
        case FabricFrame.InvokeRequest r -> {
          out.writeByte(TAG_INVOKE_REQUEST);
          out.writeLong(r.correlationId());
          writeTrace(out, r.trace());
          out.writeUTF(r.interfaceName());
          out.writeUTF(r.methodName());
          out.writeInt(r.paramTypeNames().length);
          for (String paramTypeName : r.paramTypeNames()) {
            out.writeUTF(paramTypeName);
          }
          writeBytes(out, r.serializedArgs());
        }
        case FabricFrame.InvokeResponse r -> {
          out.writeByte(TAG_INVOKE_RESPONSE);
          out.writeLong(r.correlationId());
          writeBytes(out, r.serializedReturn());
        }
        case FabricFrame.InvokeError r -> {
          out.writeByte(TAG_INVOKE_ERROR);
          out.writeLong(r.correlationId());
          writeBytes(out, r.serializedThrowable());
        }
      }
    } catch (IOException e) {
      // ByteArrayOutputStream never throws IOException in practice; this is here only because
      // DataOutputStream#write* declares it.
      throw new UncheckedIOException(e);
    }
    return buffer.toByteArray();
  }

  private static FabricFrame decodeBody(byte[] body) {
    try {
      DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(body));
      byte tag = in.readByte();
      return switch (tag) {
        case TAG_INVOKE_REQUEST -> {
          long correlationId = in.readLong();
          TraceContext trace = readTrace(in);
          String interfaceName = in.readUTF();
          String methodName = in.readUTF();
          int paramCount = in.readInt();
          String[] paramTypeNames = new String[paramCount];
          for (int i = 0; i < paramCount; i++) {
            paramTypeNames[i] = in.readUTF();
          }
          byte[] serializedArgs = readBytes(in);
          yield new FabricFrame.InvokeRequest(
              correlationId, trace, interfaceName, methodName, paramTypeNames, serializedArgs);
        }
        case TAG_INVOKE_RESPONSE -> new FabricFrame.InvokeResponse(in.readLong(), readBytes(in));
        case TAG_INVOKE_ERROR -> new FabricFrame.InvokeError(in.readLong(), readBytes(in));
        default -> throw new IllegalArgumentException("unknown fabric frame tag: " + tag);
      };
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void writeTrace(DataOutputStream out, TraceContext trace) throws IOException {
    out.writeLong(trace.traceIdHigh());
    out.writeLong(trace.traceIdLow());
    out.writeLong(trace.spanId());
    out.writeByte(trace.flags());
  }

  private static TraceContext readTrace(DataInputStream in) throws IOException {
    return new TraceContext(in.readLong(), in.readLong(), in.readLong(), in.readByte());
  }

  private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  private static byte[] readBytes(DataInputStream in) throws IOException {
    int length = in.readInt();
    checkFrameLength(length);
    byte[] bytes = new byte[length];
    in.readFully(bytes);
    return bytes;
  }
}

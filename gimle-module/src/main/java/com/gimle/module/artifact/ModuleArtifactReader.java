package com.gimle.module.artifact;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.module.descriptor.ModuleDescriptorParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Opens a module JAR, validates it's a real JPMS module, and parses its descriptor. */
public final class ModuleArtifactReader {

  private static final String DESCRIPTOR_ENTRY = "META-INF/gimle/gimle-module.yaml";
  private static final String MODULE_INFO_ENTRY = "module-info.class";

  private ModuleArtifactReader() {}

  public static ModuleArtifact read(Path jarPath) {
    if (!Files.isRegularFile(jarPath)) {
      throw new GimleManifestException("module artifact not found: " + jarPath);
    }
    try (JarFile jar = new JarFile(jarPath.toFile())) {
      if (jar.getJarEntry(MODULE_INFO_ENTRY) == null) {
        throw new GimleManifestException(
            "module artifact is not a real JPMS module (no module-info.class); automatic"
                + " modules are rejected: "
                + jarPath);
      }
      JarEntry descriptorEntry = jar.getJarEntry(DESCRIPTOR_ENTRY);
      if (descriptorEntry == null) {
        throw new GimleManifestException(
            "module artifact is missing " + DESCRIPTOR_ENTRY + ": " + jarPath);
      }
      ModuleDescriptor descriptor;
      try (InputStream in = jar.getInputStream(descriptorEntry)) {
        descriptor = ModuleDescriptorParser.parse(in);
      }
      String sha256 = sha256_of(jarPath);
      return new ModuleArtifact(descriptor.id(), jarPath, descriptor, sha256);
    } catch (IOException e) {
      throw new GimleManifestException("failed to read module artifact: " + jarPath, e);
    }
  }

  private static String sha256_of(Path jarPath) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is a mandatory JCA algorithm", e);
    }
    try (InputStream in = Files.newInputStream(jarPath)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}

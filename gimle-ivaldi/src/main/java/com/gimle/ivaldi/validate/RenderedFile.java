package com.gimle.ivaldi.validate;

/**
 * One file from a Blueprint's rendered output ({@code topology.yaml}, {@code bundle.yaml}, a {@code
 * manifests/*.yaml} entry, ...), exactly as the console's own {@code lib/render.ts} would produce
 * it. {@code path} is the file's position in that rendered tree (e.g. {@code
 * "manifests/20-web-ui-deployment.yaml"}), not a filesystem path -- {@link FileSetValidator}
 * dispatches on it but never reads or writes anything on disk.
 */
public record RenderedFile(String path, String content) {

  public RenderedFile {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("path must not be blank");
    }
    if (content == null) {
      throw new IllegalArgumentException("content must not be null");
    }
  }
}

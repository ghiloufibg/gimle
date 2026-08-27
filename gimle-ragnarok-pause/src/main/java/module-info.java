/**
 * A minimal, real hosted module -- no {@code requires} beyond the implicit {@code java.base}, no
 * exports, since nothing outside this jar ever references {@link com.gimle.ragnarok.pause.Pause}
 * directly. What makes this a real, deployable Gimle module artifact is entirely outside Java's own
 * module system: {@code META-INF/gimle/gimle-module.yaml}, read by {@code ModuleArtifactReader}
 * (not by anything JPMS-aware) -- the same shape {@code gimle-examples/hello-module} already
 * establishes.
 */
module com.gimle.ragnarok.pause {}

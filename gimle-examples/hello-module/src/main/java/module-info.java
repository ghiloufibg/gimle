/**
 * A minimal, real hosted module -- no {@code requires} beyond the implicit {@code java.base}, no
 * exports, since nothing outside this jar ever references {@link com.gimle.examples.hello.Hello}
 * directly. What makes this a real, deployable Gimle module artifact is entirely outside Java's own
 * module system: {@code META-INF/gimle/gimle-module.yaml}, read by {@code ModuleArtifactReader}
 * (not by anything JPMS-aware).
 */
module com.gimle.examples.hello {}

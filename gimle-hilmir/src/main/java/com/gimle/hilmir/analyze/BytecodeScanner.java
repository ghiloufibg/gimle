package com.gimle.hilmir.analyze;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.reflect.AccessFlag;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Scans one compiled class's bytecode for a fixed, known set of hazard signals via the JDK's own
 * {@code java.lang.classfile} API (stable since JDK 24, no ASM, no new dependency) -- a linear walk
 * of each method's own instruction stream matching specific {@code invokestatic}/{@code
 * invokevirtual}/{@code new} call sites against known method references. Deliberately not a
 * control-flow or dataflow analysis: reflection-driven or indirectly-invoked hazards (a {@code
 * System.exit} reached only through a lambda stored in a field and invoked elsewhere, a shutdown
 * hook registered via a helper method in a different class) are false negatives by design, not bugs
 * -- this is a tripwire for known failure classes, not a certification that a class is safe.
 */
public final class BytecodeScanner {

  private static final Set<String> EXECUTOR_FIELD_TYPE_HINTS =
      Set.of("ExecutorService", "ScheduledExecutorService");
  private static final Set<String> EXECUTOR_SHUTDOWN_METHOD_NAMES =
      Set.of("shutdown", "shutdownNow", "close");

  private BytecodeScanner() {}

  public static ClassHazards scan(byte[] classBytes) {
    ClassModel model = ClassFile.of().parse(classBytes);
    String binaryName = model.thisClass().asInternalName().replace('/', '.');

    boolean callsSystemExit = false;
    boolean registersShutdownHook = false;
    boolean loadsNativeLibrary = false;
    boolean opensServerSocket = false;
    boolean constructsNonDaemonThread = false;

    for (MethodModel method : model.methods()) {
      Optional<CodeModel> code = method.code();
      if (code.isEmpty()) {
        continue;
      }
      boolean methodConstructsThread = false;
      boolean methodCallsSetDaemon = false;
      for (var element : code.get()) {
        if (element instanceof InvokeInstruction invoke) {
          String owner = invoke.owner().asInternalName();
          String name = invoke.name().stringValue();
          if (owner.equals("java/lang/System") && name.equals("exit")) {
            callsSystemExit = true;
          } else if (owner.equals("java/lang/Runtime") && name.equals("addShutdownHook")) {
            registersShutdownHook = true;
          } else if (owner.equals("java/lang/System")
              && (name.equals("loadLibrary") || name.equals("load"))) {
            loadsNativeLibrary = true;
          } else if (isServerSocketCallSite(owner, name)) {
            opensServerSocket = true;
          } else if (name.equals("setDaemon")) {
            methodCallsSetDaemon = true;
          }
        } else if (element instanceof NewObjectInstruction newObj
            && newObj.className().asInternalName().equals("java/lang/Thread")) {
          methodConstructsThread = true;
        }
      }
      if (methodConstructsThread && !methodCallsSetDaemon) {
        constructsNonDaemonThread = true;
      }
    }

    List<String> declaredInterfaces =
        model.interfaces().stream().map(entry -> entry.asInternalName().replace('/', '.')).toList();
    boolean hasNoArgConstructor =
        model.methods().stream()
            .anyMatch(
                m ->
                    m.methodName().stringValue().equals("<init>")
                        && m.methodType().stringValue().equals("()V"));

    return new ClassHazards(
        binaryName,
        callsSystemExit,
        registersShutdownHook,
        constructsNonDaemonThread,
        loadsNativeLibrary,
        opensServerSocket,
        hasUnshutdownStaticExecutor(model),
        declaredInterfaces,
        hasNoArgConstructor);
  }

  // A ServerSocket/ServerSocketChannel/HttpServer construction/open call site -- the common,
  // JDK-only ways a plain module could bind a listening port the platform has no ingress story for.
  private static boolean isServerSocketCallSite(String owner, String name) {
    return (owner.equals("java/net/ServerSocket") && name.equals("<init>"))
        || (owner.equals("java/nio/channels/ServerSocketChannel") && name.equals("open"))
        || (owner.equals("com/sun/net/httpserver/HttpServer") && name.equals("create"));
  }

  /**
   * A static field whose descriptor names an {@code ExecutorService}/{@code
   * ScheduledExecutorService} and no method in this same class ever calls {@code shutdown}/{@code
   * shutdownNow}/{@code close} on anything -- same-class presence/absence only, no correlation with
   * where a lifecycle hook's own {@code onStop} might live (that class may be a different one
   * entirely), so this under-reports a leak whose shutdown call lives elsewhere and over-reports
   * nothing worse than "go check this by hand."
   */
  private static boolean hasUnshutdownStaticExecutor(ClassModel model) {
    boolean hasStaticExecutorField =
        model.fields().stream().anyMatch(BytecodeScanner::isStaticExecutorField);
    if (!hasStaticExecutorField) {
      return false;
    }
    boolean callsShutdownAnywhere =
        model.methods().stream()
            .flatMap(m -> m.code().stream())
            .flatMap(CodeModel::elementStream)
            .anyMatch(
                element ->
                    element instanceof InvokeInstruction invoke
                        && EXECUTOR_SHUTDOWN_METHOD_NAMES.contains(invoke.name().stringValue()));
    return !callsShutdownAnywhere;
  }

  private static boolean isStaticExecutorField(FieldModel field) {
    if (!field.flags().has(AccessFlag.STATIC)) {
      return false;
    }
    String descriptor = field.fieldType().stringValue();
    return EXECUTOR_FIELD_TYPE_HINTS.stream().anyMatch(descriptor::contains);
  }
}

package com.gimle.module.integration;

import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A real {@link ModuleLifecycleHooks} implementation, referenced by name from a dynamically-built
 * fixture module's descriptor and instantiated by {@code ModuleController} via {@code
 * Class.forName(name, true, handle.loader())}. It deliberately lives on the test classpath rather
 * than inside the fixture jar: a fixture resolved as a real JPMS module into its own layer gets a
 * fresh {@code Class} object for anything it {@code requires}, even for a module ({@code
 * com.gimle.module}) that's already loaded here — so bundling this class inside the fixture and
 * having it {@code implements com.gimle.module.lifecycle.ModuleLifecycleHooks} would produce an
 * object whose interface identity doesn't match the one {@code ModuleController} itself checks
 * against. Living here (found via the fixture loader's normal parent delegation to the test
 * classpath) keeps a single, consistent loading of gimle-module's types, which is what a real
 * worker JVM launch also guarantees.
 */
public class RecordingHooks implements ModuleLifecycleHooks {

  public static final List<String> CALLS = Collections.synchronizedList(new ArrayList<>());

  @Override
  public void onInstall(ModuleContext ctx) {
    CALLS.add("install");
  }

  @Override
  public void onStart(ModuleContext ctx) {
    CALLS.add("start");
  }

  @Override
  public void onStop(ModuleContext ctx) {
    CALLS.add("stop");
  }

  @Override
  public void onUninstall(ModuleContext ctx) {
    CALLS.add("uninstall");
  }
}

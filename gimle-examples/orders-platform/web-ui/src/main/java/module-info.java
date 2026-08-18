/**
 * See orders-service's own module-info.java for why Spring is never named in a "requires" clause
 * here -- it's shaded into this module's own jar at package time instead (pom.xml), and
 * java.logging/java.desktop are the two real JDK platform modules Spring's internals still need
 * regardless of shading. {@code jdk.httpserver} is the one requires unique to this module:
 * {@code com.sun.net.httpserver.HttpServer} is what serves this module's own small static page and
 * REST API -- the same JDK-only "hand-roll it, it's small" posture gimle-controlplane's own
 * ApiServer already uses, no servlet container, no web framework.
 *
 * <p>Gson is shaded in the same way, but is a genuinely different case worth calling out: unlike
 * Spring/the Postgres driver/HikariCP (every one of which ships a real module descriptor of its
 * own, even though none of that ever survives into this project's own compiled module-info.class
 * either way -- see this module's own pom.xml), the pinned Gson version here has no module
 * metadata at all, not even an Automatic-Module-Name. Its presence changes nothing about the
 * recipe -- proof the same "merge everything into one named module, keep only this project's own
 * module-info.class" approach works identically regardless of whether an upstream dependency ever
 * had JPMS awareness of its own. {@code java.sql} is the one requires this pulled in: confirmed by
 * actually running {@code Gson#toJson} through a real module-path launch of the shaded jar (not
 * just a clean compile) -- Gson's own {@code TypeAdapterFactory} chain eagerly touches {@code
 * java.sql.Time} while resolving an adapter for an entirely unrelated type, throwing {@code
 * IllegalAccessError} without this, the exact same class of failure Spring's own internals cause
 * without java.logging/java.desktop above. Nothing about this module's own code ever references
 * java.sql directly -- this requires exists purely because of what a shaded-in dependency's
 * internals reach for.
 */
module com.example.webui {
  requires static com.gimle.module;
  requires static org.slf4j;
  requires java.logging;
  requires java.desktop;
  requires java.sql;
  requires jdk.httpserver;

  // Its own copies of the two contracts it looks up over the fabric.
  exports com.example.orders;
  exports com.example.inventory;
  // WebUiHooks/WebUiLivenessProbe/WebUiReadinessProbe: ModuleController instantiates each of
  // these reflectively from outside the module.
  exports com.example.webui.provider;
}

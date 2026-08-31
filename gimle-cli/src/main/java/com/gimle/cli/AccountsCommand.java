package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code get accounts [username]}, {@code set account <username> --password <value> [--groups
 * <g1,g2,...>]} (doubles as create-or-reset, matching {@code set tenant}/{@code set config}'s
 * existing convention), {@code delete account <username>}. The password is hashed server-side
 * ({@code com.gimle.core.authz.PasswordHashes}) -- this command sends it raw, over the same
 * authenticated mTLS connection every other write already uses, and never handles hashing itself.
 * {@code --groups} is optional: omitting it preserves whatever groups the account already has (see
 * {@code ApiServer#handlePutAccount}'s own javadoc for why), so a plain password reset never
 * silently strips a {@code group:} RoleBinding's eligibility.
 */
public final class AccountsCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public AccountsCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void get(List<String> args) {
    String username = GimleCli.requireAtMostOne(args, "account");
    if (username == null) {
      OutputFormat.printList(output, client.getList("/accounts"), out);
      return;
    }
    OutputFormat.printObject(output, client.getObject("/accounts/" + username), out);
  }

  public void set(List<String> args) {
    String usage = "set account requires <username> --password <value>";
    if (args.isEmpty()) {
      throw new CliException(usage);
    }
    String username = args.get(0);
    Flags flags = Flags.parse(args.subList(1, args.size()), Set.of(), usage);
    String password = flags.get("--password");
    String groups = flags.getOrDefault("--groups", null);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("password", password);
    if (groups != null) {
      body.put("groups", Arrays.stream(groups.split(",")).map(String::trim).toList());
    }

    client.expectSuccess(client.put("/accounts/" + username, Json.write(body)));
    OutputFormat.printResult(
        output, resultBody("configured", username), "account/" + username + " configured", out);
    RbacWarnings.warnIfPlaintext(out);
  }

  public void delete(String username) {
    client.expectSuccess(client.delete("/accounts/" + username));
    OutputFormat.printResult(
        output, resultBody("deleted", username), "account/" + username + " deleted", out);
  }

  private static Map<String, Object> resultBody(String result, String username) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", result);
    body.put("kind", "account");
    body.put("username", username);
    return body;
  }
}

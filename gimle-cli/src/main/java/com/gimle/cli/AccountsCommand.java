package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code get accounts [username]}, {@code set account <username> --password <value>} (doubles as
 * create-or-reset, matching {@code set tenant}/{@code set config}'s existing convention), {@code
 * delete account <username>}. The password is hashed server-side ({@code
 * com.gimle.core.authz.PasswordHashes}) -- this command sends it raw, over the same authenticated
 * mTLS connection every other write already uses, and never handles hashing itself.
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
    if (args.isEmpty()) {
      OutputFormat.printList(output, client.getList("/accounts"), out);
      return;
    }
    String username = args.get(0);
    OutputFormat.printObject(output, client.getObject("/accounts/" + username), out);
  }

  public void set(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException("set account requires <username>");
    }
    String username = args.get(0);
    Flags flags = Flags.parse(args.subList(1, args.size()), Set.of());
    String password = flags.get("--password");

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("password", password);

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

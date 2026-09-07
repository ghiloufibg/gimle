// @vitest-environment jsdom
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import { Field } from "./clusters";

afterEach(cleanup);

describe("clusters.tsx's Field", () => {
  it("associates its label with the field's own input, resolvable via getByLabelText", () => {
    render(
      <Field label="Name">
        <input defaultValue="my-cluster" />
      </Field>,
    );

    expect((screen.getByLabelText("Name") as HTMLInputElement).value).toBe("my-cluster");
  });

  it("associates its label with a select control the same way", () => {
    render(
      <Field label="Environment">
        <select defaultValue="prod">
          <option value="prod">prod</option>
          <option value="staging">staging</option>
        </select>
      </Field>,
    );

    expect((screen.getByLabelText("Environment") as HTMLSelectElement).value).toBe("prod");
  });
});

import { describe, expect, it } from "vitest";
import { mutationScore, percent } from "./metrics";

describe("honest metric presentation", () => {
  it("distinguishes unknown values from zero", () => {
    expect(percent(null)).toBe("N/A");
    expect(percent(0)).toBe("0%");
    expect(mutationScore(null, null)).toBeNull();
    expect(mutationScore(0, 0)).toBeNull();
    expect(mutationScore(8, 2)).toBe(0.8);
  });
});

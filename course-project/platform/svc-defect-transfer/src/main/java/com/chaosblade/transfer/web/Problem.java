package com.chaosblade.transfer.web;

public class Problem extends RuntimeException {
  private final int status;
  private final String code;

  public Problem(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static Problem bad(String code, String message) {
    return new Problem(400, code, message);
  }
}

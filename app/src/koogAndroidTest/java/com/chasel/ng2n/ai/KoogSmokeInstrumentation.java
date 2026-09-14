package com.chasel.ng2n.ai;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;

// 仅跨 APK 传递平台类型，避免测试 runner 调用已被目标 APK 裁剪的 Kotlin/AndroidX API。
public final class KoogSmokeInstrumentation extends Instrumentation {
  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    start();
  }

  @Override
  public void onStart() {
    String[][] tests = {
      {"KoogAgentFactoryTest", "modelToolResultAndFinalAnswerUseTheFrameworkLoop"},
      {"DeepSeekClientFactoryTest", "deepSeekRequestsDoNotUseForumCookiesOrInterceptors"},
      {"DeepSeekClientFactoryTest", "blankKeyIsRejectedBeforeCreatingAClient"},
    };
    int failures = 0;
    for (int i = 0; i < tests.length; i++) {
      String className = "com.chasel.ng2n.data.ai." + tests[i][0];
      Bundle status = new Bundle();
      status.putString("id", "KoogSmokeInstrumentation");
      status.putString("class", className);
      status.putString("test", tests[i][1]);
      status.putInt("numtests", tests.length);
      status.putInt("current", i + 1);
      sendStatus(1, status);
      try {
        Class<?> testClass = getTargetContext().getClassLoader().loadClass(className);
        testClass.getMethod(tests[i][1]).invoke(testClass.getConstructor().newInstance());
        sendStatus(0, status);
      } catch (Throwable failure) {
        Throwable cause = failure instanceof InvocationTargetException ? failure.getCause() : failure;
        StringWriter trace = new StringWriter();
        cause.printStackTrace(new PrintWriter(trace));
        status.putString("stack", trace.toString());
        sendStatus(-2, status);
        failures++;
      }
    }
    Bundle result = new Bundle();
    result.putString("stream", "Koog offline smoke: " + tests.length + " tests, " + failures + " failures\n");
    finish(Activity.RESULT_OK, result);
  }
}

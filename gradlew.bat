@echo off
REM Wrapper jar is not bundled in this offline rebuild; call installed Gradle.
if exist "%~dp0gradle\wrapper\gradle-wrapper.jar" (
  java -classpath "%~dp0gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
) else (
  gradle %*
)

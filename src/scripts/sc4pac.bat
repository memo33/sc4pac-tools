@ECHO OFF
REM Invocation of sc4pac command-line interface on Windows.
REM In Windows cmd.exe, call:
REM
REM     sc4pac
REM
REM In Windows PowerShell, call:
REM
REM     .\sc4pac

SET SCRIPTDIR=%~dp0.

REM Basic support for authentication to Simtropolis is provided via cookies. To use this:
REM
REM - Use your web browser to sign in to Simtropolis with the "remember me" option.
REM
REM - Inspect the cookies by opening the browser Dev Tools:
REM
REM   * in Firefox: Storage > Cookies
REM
REM   * in Chrome: Application > Storage > Cookies
REM
REM - Set the environment variable `SC4PAC_SIMTROPOLIS_COOKIE` to the required cookie values of your session:
REM
REM   * Either uncomment the line below by removing the `REM` and fill in the <value> placeholders.
REM
REM   * Or set the environment variable as a User Variable in your system settings, using the same format.
REM
REM   Note that the cookies will expire after a few months.
REM
REM
REM SET "SC4PAC_SIMTROPOLIS_COOKIE=ips4_device_key=<value>; ips4_member_id=<value>; ips4_login_key=<value>"


IF "%SC4PAC_CICDEC_CMD%"=="" SET "SC4PAC_CICDEC_CMD=%SCRIPTDIR%\cicdec\cicdec.exe"

java -jar "%SCRIPTDIR%\sc4pac-cli.jar" %*

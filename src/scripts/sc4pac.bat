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

REM ------------------------------------------------------------
REM Support for authentication to Simtropolis is provided using a personal token. To use this:
REM
REM - Sign in to Simtropolis and generate your personal token at https://community.simtropolis.com/sc4pac/my-token/
REM
REM - Set the environment variable `SC4PAC_SIMTROPOLIS_TOKEN`:
REM
REM   * Either uncomment the line below by removing the `REM` and fill in the <value> placeholder.
REM
REM   * Or set the environment variable as a User Variable in your system settings, using the same format.
REM
REM SET "SC4PAC_SIMTROPOLIS_TOKEN=<value>"

REM ------------------------------------------------------------
REM Uncomment and adjust the following line to set a custom location for the profiles directory used by the GUI.
REM SET "SC4PAC_PROFILES_DIR=C:\Users\YOURUSERNAME\AppData\Roaming\io.github.memo33\sc4pac\config\profiles"

REM ------------------------------------------------------------
REM Uncomment to enable debug mode:
REM SET "SC4PAC_DEBUG=1"

REM ------------------------------------------------------------
REM Custom command for invoking the cicdec program
IF "%SC4PAC_CICDEC_CMD%"=="" SET "SC4PAC_CICDEC_CMD=%SCRIPTDIR%\cicdec\cicdec.exe"

REM ------------------------------------------------------------
REM Check if java is available or provide suitable exit code for the GUI.
where java >nul 2>nul
if %errorlevel%==1 (
    @echo Java could not be found. Please install a Java Runtime Environment and make sure it is added to your PATH environment variable during the installation.
    exit 55
)

java -jar "%SCRIPTDIR%\sc4pac-cli.jar" %*

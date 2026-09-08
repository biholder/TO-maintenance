@echo off
setlocal

cd /d "%~dp0"

if not exist ".venv" (
    echo Creating virtual environment...
    python -m venv .venv
)

call .venv\Scripts\activate.bat

echo Installing dependencies...
pip install -r requirements-build.txt

echo.
echo Building TO-Maintenance.exe ...
pyinstaller --noconfirm --onefile --windowed --name "TO-Maintenance" ^
    --add-data "templates;templates" ^
    --add-data "static;static" ^
    --collect-all pandas ^
    --collect-all openpyxl ^
    launcher.py

echo.
if exist "dist\TO-Maintenance.exe" (
    echo Done. File: dist\TO-Maintenance.exe
) else (
    echo Build failed - see the errors above.
)

pause

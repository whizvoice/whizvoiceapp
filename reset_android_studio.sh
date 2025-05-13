#!/bin/bash

#  to set up this script to work on mac
# Go to System Preferences > Security & Privacy > Accessibility
# Add Terminal (or iTerm2 if you're using that) to the list of allowed apps
# Add Android Studio to the list as well

# Step 1: Handle any open dialogs before quitting

echo "[SCRIPT] Step 1: Pre-quit dialog handler"

# Function to log all window details
log_all_windows() {
  osascript <<EOF
set output to "[WINDOW DUMP] Listing all windows and their properties..." & linefeed
try
    tell application "System Events"
        if exists (process "Android Studio") then
            tell process "Android Studio"
                set winList to windows
                repeat with w in winList
                    set winName to name of w
                    try
                        set isAXMinimized to value of attribute "AXMinimized" of w
                    on error
                        set isAXMinimized to "N/A"
                    end try
                    try
                        set winRole to role of w
                    on error
                        set winRole to "N/A"
                    end try
                    try
                        set winSubrole to subrole of w
                    on error
                        set winSubrole to "N/A"
                    end try
                    set output to output & "[WINDOW DUMP] Window: '" & winName & "' | Role: " & winRole & " | Subrole: " & winSubrole & " | AXMinimized: " & isAXMinimized & linefeed
                end repeat
            end tell
        end if
    end tell
end try
return output
EOF
}

# Log all windows before dialog handling
echo "[SCRIPT] Logging all windows BEFORE dialog handling:"
log_all_windows

# Log window states before dialog handling
osascript <<EOF
set output to "[PRE-QUIT] Window state BEFORE dialog handling:" & linefeed
try
    tell application "System Events"
        if exists (process "Android Studio") then
            tell process "Android Studio"
                set winList to windows
                repeat with w in winList
                    set winName to name of w
                    set isMinimized to miniaturized of w
                    set isVisible to visible of w
                    set output to output & "[PRE-QUIT] Window: '" & winName & "' | miniaturized: " & isMinimized & " | visible: " & isVisible & linefeed
                end repeat
            end tell
        end if
    end tell
end try
return output
EOF

maxDialogAttempts=5
preDialogAttempt=1
while [ $preDialogAttempt -le $maxDialogAttempts ]; do
  dialog_found=$(osascript <<EOF
set dialogFound to false
try
    tell application "System Events"
        if exists (process "Android Studio") then
            tell process "Android Studio"
                set winList to windows
                repeat with w in winList
                    set winName to name of w
                    set winSubrole to "N/A"
                    try
                        set winSubrole to subrole of w
                    end try
                    if winSubrole is "AXDialog" or winName is "Confirm Exit" then
                        try
                            set isAXMinimized to value of attribute "AXMinimized" of w
                        on error
                            set isAXMinimized to "N/A"
                        end try
                        log "[AXMINIMIZED][BEFORE] Window: '" & winName & "' | AXMinimized: " & isAXMinimized

                        set btns to buttons of w
                        set btnCount to count of btns
                        log "[PRE-QUIT] Found " & btnCount & " buttons in window."
                        repeat with i from 1 to btnCount
                            try
                                set btnName to name of (item i of btns)
                                log "[PRE-QUIT] Button " & i & " name: " & btnName
                            end try
                        end repeat
                        if btnCount > 0 then
                            set lastBtn to item btnCount of btns
                            log "[PRE-QUIT] Clicking button at index " & btnCount
                            click lastBtn
                            delay 1
                            set dialogFound to true
                        end if

                        try
                            set isAXMinimized to value of attribute "AXMinimized" of w
                        on error
                            set isAXMinimized to "N/A"
                        end try
                        log "[AXMINIMIZED][AFTER] Window: '" & winName & "' | AXMinimized: " & isAXMinimized
                        if isAXMinimized is true then
                            set value of attribute "AXMinimized" of w to false
                            log "[AXMINIMIZED][RESTORE] Window: '" & winName & "' was minimized and is now restored."
                        end if
                    end if
                end repeat
            end tell
        end if
    end tell
end try
return dialogFound
EOF
)
  if [ "$dialog_found" = "false" ]; then
    break
  fi
  echo "[SCRIPT] Dismissed a dialog, checking again..."
  sleep 1
  preDialogAttempt=$((preDialogAttempt+1))
done

# Log all windows after dialog handling
echo "[SCRIPT] Logging all windows AFTER dialog handling:"
log_all_windows

# Log window states after dialog handling
osascript <<EOF
set output to "[PRE-QUIT] Window state AFTER dialog handling:" & linefeed
try
    tell application "System Events"
        if exists (process "Android Studio") then
            tell process "Android Studio"
                set winList to windows
                repeat with w in winList
                    set winName to name of w
                    set isMinimized to miniaturized of w
                    set isVisible to visible of w
                    set output to output & "[PRE-QUIT] Window: '" & winName & "' | miniaturized: " & isMinimized & " | visible: " & isVisible & linefeed
                end repeat
            end tell
        end if
    end tell
end try
return output
EOF
echo "[SCRIPT] Step 1 complete"

# Wait a moment for any dialog actions to complete
sleep 2

echo "[SCRIPT] Step 2: Quit Android Studio"
osascript <<EOF
set output to "[QUIT] Sending quit command to Android Studio..." & linefeed
try
    tell application "Android Studio"
        activate
        quit
    end tell
    set output to output & "[QUIT] Quit command sent." & linefeed
on error errMsg number errNum
    set output to output & "[QUIT][ERROR] AppleScript error: " & errMsg & " (Error number: " & errNum & ")" & linefeed
end try
return output
EOF
echo "[SCRIPT] Step 2 complete"

# Retry loop to handle all dialogs after quit command
maxDialogAttempts=10
postDialogAttempt=1
while [ $postDialogAttempt -le $maxDialogAttempts ]; do
  dialog_found=$(osascript <<EOF
set dialogFound to false
try
    tell application "System Events"
        if exists (process "Android Studio") then
            tell process "Android Studio"
                set winList to windows
                repeat with w in winList
                    set winName to name of w
                    if winName is not "" and winName does not contain "[whiz.app.main]" then
                        set btns to buttons of w
                        set btnCount to count of btns
                        if btnCount > 0 then
                            set lastBtn to item btnCount of btns
                            click lastBtn
                            delay 1
                            set dialogFound to true
                        end if
                    end if
                end repeat
            end tell
        end if
    end tell
end try
return dialogFound
EOF
)
  if [ "$dialog_found" = "false" ]; then
    break
  fi
  echo "[SCRIPT] Dismissed a post-quit dialog, checking again..."
  sleep 1
  postDialogAttempt=$((postDialogAttempt+1))
done

# Wait for Android Studio to fully quit
sleep 2

# Verify Android Studio has actually quit
echo "[SCRIPT] Verifying Android Studio has quit..."
maxAttempts=10
attempt=1
while [ $attempt -le $maxAttempts ]; do
    # Check both the process name and the bundle identifier
    if ! pgrep -f "Android Studio.app" > /dev/null && ! pgrep -f "com.google.android.studio" > /dev/null; then
        echo "[SCRIPT] Android Studio has quit successfully."
        break
    fi
    echo "[SCRIPT] Android Studio still running, attempt $attempt of $maxAttempts"
    sleep 2
    attempt=$((attempt+1))
done

if [ $attempt -gt $maxAttempts ]; then
    echo "[SCRIPT] Failed to quit Android Studio after $maxAttempts attempts. Exiting."
    exit 1
fi

# After quitting Android Studio, log all windows
echo "[SCRIPT] Logging all windows AFTER quit attempt:"
log_all_windows

# Step 3: Handle any open dialogs after quitting

echo "[SCRIPT] Step 3: Post-quit dialog handler"
osascript <<EOF
set output to "[POST-QUIT] Checking for any dialog windows..." & linefeed
try
    tell application "System Events"
        if exists (process "Android Studio") then
            tell process "Android Studio"
                set winList to windows
                set output to output & "[POST-QUIT] Windows found: " & (count of winList) & linefeed
                repeat with w in winList
                    try
                        set winName to name of w
                        set output to output & "[POST-QUIT] Window name: " & winName & linefeed
                        set btns to buttons of w
                        set btnCount to count of btns
                        set output to output & "[POST-QUIT] Buttons in window: " & btnCount & linefeed
                        repeat with b in btns
                            try
                                set btnName to name of b
                                set output to output & "[POST-QUIT] Button name: " & btnName & linefeed
                            end try
                        end repeat
                        if btnCount > 0 then
                            set lastBtn to item btnCount of btns
                            set output to output & "[POST-QUIT] Clicking last button in window '" & winName & "' (index: " & btnCount & ")" & linefeed
                            click lastBtn
                            delay 1
                        end if
                    end try
                end repeat
            end tell
        else
            set output to output & "[POST-QUIT] Android Studio process not found during post-quit check." & linefeed
        end if
    end tell
on error errMsg number errNum
    set output to output & "[POST-QUIT][ERROR] AppleScript error: " & errMsg & " (Error number: " & errNum & ")" & linefeed
end try
return output
EOF
echo "[SCRIPT] Step 3 complete"

# Wait for Android Studio to fully quit
sleep 2

echo "[SCRIPT] Step 4: Reopen Android Studio"
open -a "Android Studio" .
sleep 5  # Increased delay to ensure windows have time to initialize
osascript <<EOF
set output to "[REOPEN] Reopening Android Studio..." & linefeed
try
    tell application "Android Studio"
        activate
        delay 3  # Increased delay after activation
    end tell
    set output to output & "[REOPEN] Android Studio activated and windows restored." & linefeed
    -- Log AXMinimized state after reopen
    tell application "System Events"
        if exists (process "Android Studio") then
            tell process "Android Studio"
                set winList to windows
                repeat with w in winList
                    set winName to name of w
                    try
                        set isAXMinimized to value of attribute "AXMinimized" of w
                    on error
                        set isAXMinimized to "N/A"
                    end try
                    set output to output & "[AXMINIMIZED][REOPEN] Window: '" & winName & "' | AXMinimized: " & isAXMinimized & linefeed
                end repeat
            end tell
        end if
    end tell
on error errMsg number errNum
    set output to output & "[REOPEN][ERROR] AppleScript error: " & errMsg & " (Error number: " & errNum & ")" & linefeed
end try
return output
EOF

# Wait for Android Studio to launch and load UI
sleep 15  # Increased delay to ensure UI is fully loaded

echo "[SCRIPT] Step 5: Logging all window UI elements for identification"
osascript <<EOF
set output to "[UI DUMP] Listing all windows and their UI elements..." & linefeed
try
    tell application "System Events"
        if exists (process "Android Studio") then
            tell process "Android Studio"
                set winList to windows
                repeat with w in winList
                    set winName to name of w
                    set output to output & "[UI DUMP] Window: '" & winName & "'\n"
                    set groupsList to groups of w
                    set groupIndex to 1
                    repeat with g in groupsList
                        set groupDesc to description of g
                        set output to output & "  [Group " & groupIndex & "] Description: '" & groupDesc & "'\n"
                        set staticTexts to static texts of g
                        set staticIndex to 1
                        repeat with t in staticTexts
                            set output to output & "    [StaticText " & staticIndex & "] Value: '" & value of t & "'\n"
                            set staticIndex to staticIndex + 1
                        end repeat
                        set buttonsList to buttons of g
                        set buttonIndex to 1
                        repeat with b in buttonsList
                            set output to output & "    [Button " & buttonIndex & "] Name: '" & name of b & "'\n"
                            set buttonIndex to buttonIndex + 1
                        end repeat
                        set groupIndex to groupIndex + 1
                    end repeat
                end repeat
            end tell
        end if
    end tell
end try
return output
EOF

echo "[SCRIPT] Step 5: Wait for status bar to be empty, then send Command+R to trigger Run"

maxAttempts=10
attempt=1
buildStarted=0
while [ $attempt -le $maxAttempts ] && [ $buildStarted -eq 0 ]; do
  echo "[SCRIPT] Attempt $attempt: Checking status bar..."
  status_bar_text=$(osascript <<EOF
set statusText to ""
try
    tell application "System Events"
        if exists (process "Android Studio") then
            tell process "Android Studio"
                set winList to windows
                repeat with w in winList
                    try
                        set groupsList to groups of w
                        repeat with g in groupsList
                            if description of g is "Status Bar" then
                                set staticTexts to static texts of g
                                repeat with t in staticTexts
                                    set statusText to statusText & value of t & ";"
                                end repeat
                                -- Log all static text values in the status bar
                                set output to "[STATUS BAR] Window: '" & name of w & "'\n"
                                repeat with t in staticTexts
                                    set output to output & "[STATUS BAR] Static text: '" & value of t & "'\n"
                                end repeat
                                log output
                            end if
                        end repeat
                    end try
                end repeat
            end tell
        end if
    end tell
end try
return statusText
EOF
)
  echo "[SCRIPT] Status bar text: $status_bar_text"
  # Only proceed if status bar is empty or contains only 'ready' or similar
  if [ -z "$status_bar_text" ] || [[ "$status_bar_text" =~ ready ]]; then
    echo "[SCRIPT] Status bar is empty or ready. Bringing Android Studio to front and sending Command+R."
    osascript <<EOF
    set maxTries to 5
    set foundFrontmost to false
    repeat with i from 1 to maxTries
        tell application "Android Studio" to activate
        delay 1
        tell application "System Events"
            set frontApp to name of first application process whose frontmost is true
            log "[DEBUG] Frontmost app after activate: " & frontApp
            if frontApp is "Android Studio" or frontApp is "studio" then
                tell process frontApp
                    set mainWin to null
                    try
                        -- Prefer AXMain if available and reliable
                        set mainWin to first window whose value of attribute "AXMain" is true
                        log "[DEBUG] Found main window by AXMain: " & name of mainWin
                    on error
                        -- Fallback to name if AXMain fails or isn't specific enough
                        log "[DEBUG] AXMain failed or not found, trying by name."
                        try
                            set mainWin to first window whose name contains "[whiz.app.main]"
                            log "[DEBUG] Found main window by name: " & name of mainWin
                        on error
                            log "[DEBUG] Could not find main window by AXMain or name."
                            exit repeat -- Exit this attempt if no main window found
                        end try
                    end try

                    if mainWin is not null then
                        try
                            log "[DEBUG] Attempting to set AXFocused for window: " & name of mainWin
                            set value of attribute "AXFocused" of mainWin to true
                            delay 0.5
                            set isWinFocused to value of attribute "AXFocused" of mainWin
                            log "[DEBUG] Window AXFocused attribute after attempt: " & isWinFocused
                        on error errMsg
                            log "[DEBUG] Error setting AXFocused for window: " & errMsg
                        end try

                        -- Focus editor
                        log "[DEBUG] Sending Cmd+1 and Esc to focus editor."
                        keystroke "1" using {command down}
                        delay 0.5
                        keystroke (ASCII character 27) -- Esc
                        delay 0.5

                        -- Log focus state RIGHT BEFORE sending Cmd+R
                        set finalWinFocusedState to "N/A"
                        set finalFocusedElement to "N/A"
                        try
                            set finalWinFocusedState to value of attribute "AXFocused" of mainWin
                        end try
                        try
                            set finalFocusedElement to description of (value of attribute "AXFocusedUIElement" of process frontApp)
                        on error
                             set finalFocusedElement to "Error getting focused UI element or no element focused"
                        end try
                        log "[DEBUG][PRE-CMD+R] Window AXFocused: " & finalWinFocusedState
                        log "[DEBUG][PRE-CMD+R] Process AXFocusedUIElement: " & finalFocusedElement

                        log "[DEBUG] Sending Control+R"
                        keystroke "r" using {control down}
                        set foundFrontmost to true
                        exit repeat
                    end if
                end tell
            end if
        end tell
    end repeat
    if foundFrontmost is false then
        log "[ERROR] Could not bring Android Studio to front after several attempts."
    end if
EOF
    sleep 2
    # Log all static text values in the status bar after Command+R
    echo "[SCRIPT] Logging all static text values in status bar after Command+R:"
    osascript <<EOF
set output to ""
try
    tell application "System Events"
        if exists (process "Android Studio") then
            tell process "Android Studio"
                set winList to windows
                repeat with w in winList
                    try
                        set winName to name of w
                        set groupsList to groups of w
                        repeat with g in groupsList
                            if description of g is "Status Bar" then
                                set output to output & "[STATUS BAR] Window: '" & winName & "'\n"
                                set staticTexts to static texts of g
                                repeat with t in staticTexts
                                    set output to output & "[STATUS BAR] Static text: '" & value of t & "'\n"
                                end repeat
                            end if
                        end repeat
                    end try
                end repeat
            end tell
        end if
    end tell
end try
return output
EOF
    # Check if build started
    build_status=$(osascript <<EOF
set buildRunning to false
set foundStatusBar to false
set checkedText to ""
try
    tell application "System Events"
        if exists (process "Android Studio") then
            tell process "Android Studio"
                set winList to windows
                repeat with w in winList
                    try
                        set groupsList to groups of w
                        repeat with g in groupsList
                            -- Try to identify the Status Bar group by description or name
                            set groupDesc to ""
                            try
                                set groupDesc to description of g
                            end try
                            set groupName to ""
                            try
                                set groupName to name of g
                            end try

                            if groupDesc is "Status Bar" or groupName is "Status Bar" then
                                set foundStatusBar to true
                                -- log "[BUILD_CHECK] Found Status Bar in window: " & (name of w as text) & " (Description: '" & groupDesc & "', Name: '" & groupName & "')"
                                set staticTexts to static texts of g
                                repeat with t in staticTexts
                                    try
                                        set currentTextValue to value of t
                                        set checkedText to checkedText & "|'" & currentTextValue & "'"
                                        -- log "[BUILD_CHECK] Checking text: '" & currentTextValue & "'"
                                        if currentTextValue contains "Gradle Build Running" then
                                            -- log "[BUILD_CHECK] Match found: 'Gradle Build Running'"
                                            set buildRunning to true
                                            exit repeat -- Exit staticTexts loop
                                        end if
                                    on error errMsgValue
                                        -- log "[BUILD_CHECK][ERROR] Error getting value of static text: " & errMsgValue
                                    end try
                                end repeat
                                if buildRunning then exit repeat -- Exit groupsList loop
                            end if
                        end repeat
                        if buildRunning then exit repeat -- Exit winList loop
                    end try
                end repeat
            end tell
        else
            -- log "[BUILD_CHECK] Android Studio process not found."
        end if
    end tell
on error errMsg
    -- log "[BUILD_CHECK][ERROR] AppleScript error: " & errMsg
end try
-- log "[BUILD_CHECK] Final buildRunning status: " & buildRunning & ", Found Status Bar: " & foundStatusBar & ", Checked Texts: " & checkedText
return buildRunning
EOF
)
    echo "[SCRIPT] Build running: $build_status"
    if [ "$build_status" = "true" ]; then
      buildStarted=1
      echo "[SCRIPT] Build started successfully."
    else
      echo "[SCRIPT] Build did not start. Retrying..."
      sleep 3
    fi
  else
    echo "[SCRIPT] Status bar not empty or not ready. Waiting..."
    sleep 3
  fi
  attempt=$((attempt+1))
done
if [ $buildStarted -eq 0 ]; then
  echo "[SCRIPT] Failed to start build after $maxAttempts attempts."
fi
echo "[SCRIPT] Step 5 complete"

# Log AXMinimized state after reopening Android Studio
osascript <<EOF
set output to "[AXMINIMIZED] Window state after reopen:" & linefeed
try
    tell application "System Events"
        if exists (process "Android Studio") then
            tell process "Android Studio"
                set winList to windows
                repeat with w in winList
                    set winName to name of w
                    try
                        set isAXMinimized to value of attribute "AXMinimized" of w
                    on error
                        set isAXMinimized to "N/A"
                    end try
                    set output to output & "[AXMINIMIZED] Window: '" & winName & "' | AXMinimized: " & isAXMinimized & linefeed
                end repeat
            end tell
        end if
    end tell
end try
return output
EOF

# After reopening Android Studio, log all windows
echo "[SCRIPT] Logging all windows AFTER reopen:"
log_all_windows

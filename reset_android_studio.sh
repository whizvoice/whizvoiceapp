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
  dialog_info_raw=$(osascript <<EOF
set dialogFound to false
set outputLog to ""
try
    tell application "Android Studio" to activate -- Ensure AS is frontmost
    delay 0.5 -- Give a moment for activation
    tell application "System Events"
        if exists (process "Android Studio") then
            tell process "Android Studio"
                set winList to windows
                
                -- First, specifically look for "Process 'app' is Running" dialog
                repeat with w in winList
                    set winName to name of w
                    if winName contains "Process 'app' is Running" then
                        set outputLog to outputLog & "[PRE-QUIT] Found 'Process \\"app\\" is Running' dialog. Window: '" & winName & "'. Attempting to click its last button.\\n"
                        try
                            set btns to buttons of w
                            set btnCount to count of btns
                            if btnCount > 0 then
                                set outputLog to outputLog & "[PRE-QUIT] 'Process \\"app\\"...' dialog has " & btnCount & " buttons. Clicking button " & btnCount & " (last one).\\n"
                                -- Log all button names/states for this dialog
                                repeat with i from 1 to btnCount
                                    try
                                        set btnDetail to "Button " & i & " name: " & (name of (item i of btns))
                                    on error
                                        set btnDetail to "Button " & i & " name: [missing value]"
                                    end try
                                    set outputLog to outputLog & "[PRE-QUIT] " & btnDetail & "\\n"
                                end repeat
                                click item -1 of btns -- Click the last button
                                delay 0.2 -- Short delay after click
                                tell application "System Events" to key code 36 -- Press Enter/Return
                                set outputLog to outputLog & "[PRE-QUIT] Clicked last button and sent Enter key in 'Process \\"app\\" is Running' dialog.\\n"
                                set dialogFound to true
                                delay 1
                            else
                                set outputLog to outputLog & "[PRE-QUIT] 'Process \\"app\\" is Running' dialog has no buttons.\\n"
                            end if
                        on error err_click_last
                            set outputLog to outputLog & "[PRE-QUIT] Failed to click last button in 'Process \\"app\\" is Running' dialog: " & err_click_last & "\\n"
                        end try
                        return outputLog & "DialogFound:" & dialogFound -- Exit after attempting to handle this specific dialog
                    end if
                end repeat

                -- If specific dialog not handled, proceed with generic AXDialog check
                if dialogFound is false then
                repeat with w in winList
                    set winName to name of w
                    set winSubrole to "N/A"
                    try
                        set winSubrole to subrole of w
                    end try
                        if winSubrole is "AXDialog" then
                            set outputLog to outputLog & "[PRE-QUIT] Found AXDialog: '" & winName & "'.\n"
                        try
                            set isAXMinimized to value of attribute "AXMinimized" of w
                        on error
                            set isAXMinimized to "N/A"
                        end try
                            set outputLog to outputLog & "[AXMINIMIZED][BEFORE] Window: '" & winName & "' | AXMinimized: " & isAXMinimized & "\n"

                        set btns to buttons of w
                        set btnCount to count of btns
                            set outputLog to outputLog & "[PRE-QUIT] Found " & btnCount & " buttons in AXDialog.\n"
                        repeat with i from 1 to btnCount
                            try
                                    set btnName_generic to name of (item i of btns)
                                    set outputLog to outputLog & "[PRE-QUIT] AXDialog Button " & i & " name: " & btnName_generic & "\n"
                                on error
                                     set outputLog to outputLog & "[PRE-QUIT] AXDialog Button " & i & " name: [missing value]\n"
                            end try
                        end repeat
                        if btnCount > 0 then
                                set outputLog to outputLog & "[PRE-QUIT] Clicking last button in AXDialog '" & winName & "'.\\n"
                                click item -1 of btns -- Click the last button
                                delay 0.2 -- Short delay after click
                                tell application "System Events" to key code 36 -- Press Enter/Return
                                set outputLog to outputLog & "[PRE-QUIT] Clicked last button and sent Enter key in AXDialog '" & winName & "'.\\n"
                            set dialogFound to true
                        end if

                        try
                            set isAXMinimized to value of attribute "AXMinimized" of w
                        on error
                            set isAXMinimized to "N/A"
                        end try
                            set outputLog to outputLog & "[AXMINIMIZED][AFTER] Window: '" & winName & "' | AXMinimized: " & isAXMinimized & "\n"
                        if isAXMinimized is true then
                            set value of attribute "AXMinimized" of w to false
                                set outputLog to outputLog & "[AXMINIMIZED][RESTORE] Window: '" & winName & "' was minimized and is now restored.\n"
                            end if
                            exit repeat -- Handle one AXDialog per pass
                        end if
                    end repeat
                    end if
            end tell
        end if
    end tell
on error errMsg
    set outputLog to outputLog & "[PRE-QUIT][ERROR] Main error: " & errMsg & "\n"
end try
return outputLog & "DialogFound:" & dialogFound
EOF
)
  echo "$dialog_info_raw" # Echo the full log from AppleScript
  if [[ "$dialog_info_raw" != *"DialogFound:true"* ]]; then # Check the appended flag
    break
  fi
  echo "[SCRIPT] Dismissed a dialog (or tried to), checking again..."
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
set outputText to "[QUIT] Attempting to quit Android Studio with integrated dialog handling...\\n"
set quitSuccessful to false
set quitAttempts to 0
set maxQuitAttempts to 3 -- Try to quit and handle dialogs up to 3 times

repeat while quitSuccessful is false and quitAttempts < maxQuitAttempts
    set quitAttempts to quitAttempts + 1
    set outputText to outputText & "[QUIT ATTEMPT " & quitAttempts & "] Sending quit command.\\n"
try
    tell application "Android Studio"
        activate
        quit
    end tell
        set quitSuccessful to true
        set outputText to outputText & "[QUIT ATTEMPT " & quitAttempts & "] Quit command sent, no immediate error.\\n"
on error errMsg number errNum
        set outputText to outputText & "[QUIT ATTEMPT " & quitAttempts & "] Error during quit: " & errMsg & " (Number: " & errNum & "). Attempting to handle dialogs.\\n"
        -- If quit is interrupted, try to handle dialogs immediately
        delay 0.5 -- Give dialog a moment to fully appear
    tell application "System Events"
        if exists (process "Android Studio") then
            tell process "Android Studio"
                    set winList_quit to windows
                    set dialogHandledInQuitAttempt to false
                    repeat with w_quit in winList_quit
                        set winName_quit to name of w_quit
                        -- Target "Process 'app' is Running" specifically
                        if winName_quit contains "Process 'app' is Running" then
                            set outputText to outputText & "[QUIT DIALOG HANDLER] Found 'Process \\"app\\" is Running' dialog. Clicking last button and sending Enter.\\n"
                            try
                                set btns_quit to buttons of w_quit
                                if (count of btns_quit) > 0 then
                                    click item -1 of btns_quit
                                    delay 0.2
                                    tell application "System Events" to key code 36 -- Press Enter/Return
                                    set outputText to outputText & "[QUIT DIALOG HANDLER] Clicked last button and sent Enter.\\n"
                                    set dialogHandledInQuitAttempt to true
                                    delay 1 -- Wait a bit after handling
                                else
                                    set outputText to outputText & "[QUIT DIALOG HANDLER] 'Process \\"app\\" is Running' dialog has no buttons.\\n"
                                end if
                            on error clickErr
                                set outputText to outputText & "[QUIT DIALOG HANDLER] Error clicking button: " & clickErr & "\\n"
                            end try
                            exit repeat -- Handled the specific dialog
                        end if
                    end repeat -- end window loop for dialog handling

                    -- Generic AXDialog if specific one not found or if loop continues
                    if dialogHandledInQuitAttempt is false then
                        set outputText to outputText & "[QUIT DIALOG HANDLER] 'Process \\"app\\" is Running' not found, or failed. Checking for generic AXDialog.\\n"
                        repeat with w_quit_generic in winList_quit
                             try
                                if subrole of w_quit_generic is "AXDialog" then
                                    set outputText to outputText & "[QUIT DIALOG HANDLER] Found generic AXDialog: '" & (name of w_quit_generic) & "'. Clicking last button and sending Enter.\\n"
                                    set btns_generic_quit to buttons of w_quit_generic
                                    if (count of btns_generic_quit) > 0 then
                                        click item -1 of btns_generic_quit
                                        delay 0.2
                                        tell application "System Events" to key code 36 -- Press Enter/Return
                                        set outputText to outputText & "[QUIT DIALOG HANDLER] Clicked last button and sent Enter for generic AXDialog.\\n"
                                        delay 1 -- Wait a bit
                                    else
                                        set outputText to outputText & "[QUIT DIALOG HANDLER] Generic AXDialog has no buttons.\\n"
                                    end if
                                    exit repeat -- Handled one generic dialog
                                end if
                            on error subroleErr
                                set outputText to outputText & "[QUIT DIALOG HANDLER] Error checking subrole or interacting with generic dialog: " & subroleErr & "\\n"
                            end try
                        end repeat
                    end if

            end tell
            else
                set outputText to outputText & "[QUIT DIALOG HANDLER] Android Studio process not found during error handling.\\n"
                set quitSuccessful to true -- Assume it quit if process is gone
            end if
        end tell -- System Events
        
        if quitSuccessful is false and quitAttempts < maxQuitAttempts then
             set outputText to outputText & "[QUIT ATTEMPT " & quitAttempts & "] Dialog handling complete or no dialogs found, will retry quit command after a delay.\\n"
             delay 1 -- Wait before retrying quit
        end if

end try
end repeat

if quitSuccessful is false then
    set outputText to outputText & "[QUIT] Failed to quit Android Studio cleanly after " & maxQuitAttempts & " attempts.\\n"
else
    set outputText to outputText & "[QUIT] Android Studio quit process completed (either successfully or process disappeared).\\n"
end if
return outputText
EOF
echo "[SCRIPT] Step 2 complete"

echo "[SCRIPT] Pausing for 3 seconds to allow Android Studio to fully close if it was successful..."
# This delay is now more about giving AS time to close down if the quit command worked,
# before we attempt to reopen it.
sleep 3

# Retry loop to handle all dialogs after quit command
# maxDialogAttempts=10
# postDialogAttempt=1
# while [ $postDialogAttempt -le $maxDialogAttempts ]; do
#   echo "[SCRIPT] Post-quit dialog check attempt: $postDialogAttempt"
#   dialog_info=$(osascript <<EOF
# set dialogFound to false
# set outputText to ""
# 
# try
#     tell application "Android Studio" to activate -- Ensure AS is frontmost
#     delay 0.5 -- Give a moment for activation
#     tell application "System Events"
#         if exists (process "Android Studio") then
#             tell process "Android Studio"
#                 set winList to windows
#                 if (count of winList) is 0 then
#                     set outputText to outputText & "[POST-QUIT-LOOP] No windows found for Android Studio.\\n"
#                 else
#                     set outputText to outputText & "[POST-QUIT-LOOP] Windows found: " & (count of winList) & "\\n"
#                 end if
# 
#                 repeat with w in winList
#                     set winName to name of w
#                     set winSubrole to "N/A"
#                     set winRole to "N/A"
#                     try
#                         set winSubrole to subrole of w
#                     end try
#                     try
#                         set winRole to role of w
#                     end try
#                     set outputText to outputText & "[POST-QUIT-LOOP] Checking window: '" & winName & "', Role: " & winRole & ", Subrole: " & winSubrole & ".\\n"
#                     
#                     set handledInThisWindow to false
#                     -- Specifically look for "Process 'app' is Running" dialog
#                     if winName contains "Process 'app' is Running" then
#                         set outputText to outputText & "[POST-QUIT-LOOP] Found 'Process \\"app\\" is Running' dialog. Attempting to click its last button and send Enter.\\n"
#                         try
#                             set btns_proc to buttons of w
#                             set btn_proc_count to count of btns_proc
#                             if btn_proc_count > 0 then
#                                 set outputText to outputText & "[POST-QUIT-LOOP] 'Process \\"app\\"...' dialog has " & btn_proc_count & " buttons. Clicking button " & btn_proc_count & " (last one).\\n"
#                                 repeat with i from 1 to btn_proc_count
#                                     try
#                                         set btnDetail_post to "Button " & i & " name: " & (name of (item i of btns_proc))
#                                     on error
#                                         set btnDetail_post to "Button " & i & " name: [missing value]"
#                                     end try
#                                     set outputText to outputText & "[POST-QUIT-LOOP] " & btnDetail_post & "\\n"
#                                 end repeat
#                                 click item -1 of btns_proc -- Click the last button
#                                 delay 0.2 -- Short delay after click
#                                 tell application "System Events" to key code 36 -- Press Enter/Return
#                                 set outputText to outputText & "[POST-QUIT-LOOP] 'Process \\"app\\"...': Clicked last button and sent Enter.\\n"
#                                 set dialogFound to true
#                                 set handledInThisWindow to true
#                             else
#                                 set outputText to outputText & "[POST-QUIT-LOOP] 'Process \\"app\\"...' dialog has no buttons.\\n"
#                             end if
#                         on error err_proc_last
#                             set outputText to outputText & "[POST-QUIT-LOOP] 'Process \\"app\\"...': Error interacting with buttons: " & err_proc_last & ".\\n"
#                         end try
#                         if handledInThisWindow is true then
#                             delay 1
#                             -- Do not exit repeat here, let it log all windows, then decide based on dialogFound
#                         end if
#                     end if
# 
#                     -- Generic AXDialog check (only if not the specific dialog handled above for this window)
#                     if handledInThisWindow is false and winSubrole is "AXDialog" then
#                         set outputText to outputText & "[POST-QUIT-LOOP] Found generic AXDialog: '" & winName & "'. Attempting to click its last button and send Enter.\\n"
#                         try
#                             set btns to buttons of w
#                             set btn_count to count of btns
#                             set outputText to outputText & "[POST-QUIT-LOOP] AXDialog '" & winName & "' has " & btn_count & " buttons. Button details: \\n"
#                             repeat with i from 1 to btn_count
#                                 try
#                                     set outputText to outputText & "  Button " & i & " name: " & (name of button i of w) & "\\n"
#                                 on error
#                                     set outputText to outputText & "  Button " & i & " name: [missing value]\\n"
#                                 end try
#                             end repeat
# 
#                             if btn_count > 0 then
#                                 set outputText to outputText & "[POST-QUIT-LOOP] Clicking last button (index " & btn_count & ") in AXDialog '" & winName & "'.\\n"
#                                 click item -1 of btns -- Click the last button
#                                 delay 0.2 -- Short delay after click
#                                 tell application "System Events" to key code 36 -- Press Enter/Return
#                                 set outputText to outputText & "[POST-QUIT-LOOP] Clicked last button and sent Enter in AXDialog '" & winName & "'.\\n"
#                                 set dialogFound to true
#                                 set handledInThisWindow to true
#                             else
#                                 set outputText to outputText & "[POST-QUIT-LOOP] AXDialog '" & winName & "' has no buttons to click.\\n"
#                             end if
#                         on error err_click
#                             set outputText to outputText & "[POST-QUIT-LOOP] Error clicking/sending Enter to last button in AXDialog '" & winName & "': " & err_click & "\\n"
#                         end try
#                          if handledInThisWindow is true then
#                             delay 1
#                             -- Do not exit repeat here, let it log all windows, then decide based on dialogFound
#                         end if
#                     end if
# 
#                     -- If this window was handled, we set dialogFound to true. 
#                     -- The script will loop again if dialogFound is true after checking all windows in this pass.
#                 end repeat -- end window loop
#             end tell
#         else
#             set outputText to outputText & "[POST-QUIT-LOOP] Android Studio process not found.\\n"
#         end if
#     end tell
# on error errMsg
#     set outputText to outputText & "[POST-QUIT-LOOP][ERROR] Main AppleScript error: " & errMsg & "\\n"
# end try
# return outputText & "DialogFound:" & dialogFound
# EOF
# )
#   echo "$dialog_info" # Echo the full log from AppleScript
#   if [[ "$dialog_info" != *"DialogFound:true"* ]]; then # Check the appended flag
#     break
#   fi
#   echo "[SCRIPT] Dismissed a post-quit dialog (or tried to), checking again..."
#   sleep 1
#   postDialogAttempt=$((postDialogAttempt+1))
# done

# Wait for Android Studio to fully quit
# sleep 2 # This sleep was associated with the old Step 3 and verification, likely not needed if quit is robust.

# Verify Android Studio has actually quit
# echo "[SCRIPT] Verifying Android Studio has quit..."
# maxAttempts=10
# attempt=1
# while [ $attempt -le $maxAttempts ]; do
#     # Check both the process name and the bundle identifier
#     if ! pgrep -f "Android Studio.app" > /dev/null && ! pgrep -f "com.google.android.studio" > /dev/null; then
#         echo "[SCRIPT] Android Studio has quit successfully."
#         break
#     fi
#     echo "[SCRIPT] Android Studio still running, attempt $attempt of $maxAttempts"
#     sleep 2
#     attempt=$((attempt+1))
# done
# 
# if [ $attempt -gt $maxAttempts ]; then
#     echo "[SCRIPT] Failed to quit Android Studio after $maxAttempts attempts. Exiting."
#     exit 1 # Exit if AS didn't quit.
# fi

# After quitting Android Studio, log all windows
echo "[SCRIPT] Logging all windows AFTER quit attempt (and before reopen):"
# log_all_windows # This might be problematic if AS is truly gone, or confusing if it's restarting.
# Let's disable this specific log_all_windows for now to avoid potential issues with a non-existent/restarting process.

# Step 4: Reopen Android Studio
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
    sleep 0.5
    # Log all static text values in the status bar after Command+R
    echo "[SCRIPT] Logging all static text values in status bar after Control+R (attempting build_status check with UI DUMP):"
    
    raw_build_check_output=$(osascript <<'APPLESCRIPT_EOF'
set buildRunning to false
set uiDumpLog to "[UI DUMP START]\\n"
set checkAttempts to 0
set maxCheckAttempts to 5 -- Poll 5 times

repeat while buildRunning is false and checkAttempts < maxCheckAttempts
    set checkAttempts to checkAttempts + 1
try
    tell application "System Events"
            tell application process "Android Studio"
                if not (exists) then
                    set uiDumpLog to uiDumpLog & "  Android Studio process does not exist.\\n"
                    set uiDumpLog to uiDumpLog & "[UI DUMP END]"
                    return "buildRunning:false|uiDump:" & uiDumpLog
                end if
                set uiDumpLog to uiDumpLog & "  Android Studio process exists.\\n"

                set targetWindow to null
                set uiDumpLog to uiDumpLog & "  Trying to find target window...\\n"
                try
                    set targetWindow to first window whose value of attribute "AXMain" is true
                    set uiDumpLog to uiDumpLog & "    Found main window by AXMain: '" & (name of targetWindow default "N/A") & "'\\n"
                on error
                    set uiDumpLog to uiDumpLog & "    AXMain failed. Trying by name '[whiz.app.main]'...\\n"
                    set matchingWindows to (windows whose name contains "[whiz.app.main]")
                    if (count of matchingWindows) > 0 then
                        set targetWindow to item 1 of matchingWindows
                        set uiDumpLog to uiDumpLog & "    Found window by name: '" & (name of targetWindow default "N/A") & "'\\n"
                    else
                        set uiDumpLog to uiDumpLog & "    No window found by name. Checking if frontmost...\\n"
                        if frontmost is true then
                            try
                                set targetWindow to window 1
                                set uiDumpLog to uiDumpLog & "    Using frontmost window 1: '" & (name of targetWindow default "N/A") & "'\\n"
                            on error err_win1
                                set uiDumpLog to uiDumpLog & "    Error getting window 1: " & err_win1 & "\\n"
                                if checkAttempts < maxCheckAttempts then
                                    -- delay 0.5 -- Handled by main loop delay
                                else
                                    set uiDumpLog to uiDumpLog & "    Exhausted attempts to find window.\\n"
                                    set uiDumpLog to uiDumpLog & "[UI DUMP END]"
                                    return "buildRunning:false|uiDump:" & uiDumpLog
                                end if
                            end try
                        else
                             set uiDumpLog to uiDumpLog & "    Not frontmost.\\n"
                             if checkAttempts < maxCheckAttempts then
                                -- delay 0.5 -- Handled by main loop delay
                            else
                                 set uiDumpLog to uiDumpLog & "    Exhausted attempts (not frontmost).\\n"
                                 set uiDumpLog to uiDumpLog & "[UI DUMP END]"
                                 return "buildRunning:false|uiDump:" & uiDumpLog
                            end if
                        end if
                    end if
                end try
                
                if targetWindow is null then
                     set uiDumpLog to uiDumpLog & "  Target window is null for this check (will retry if attempts left).\\n"
                else
                    set uiDumpLog to uiDumpLog & "  Target window confirmed: '" & (name of targetWindow default "N/A") & "'. Proceeding to find status bar.\\n"
                    set statusBarGroup to null
                    set uiDumpLog to uiDumpLog & "    Trying to find status bar group...\\n"
                    try
                        set allUIElementsInWindow to entire contents of targetWindow
                        set uiDumpLog to uiDumpLog & "      Iterating " & (count of allUIElementsInWindow) & " UI elements in window to find Status Bar Group...\\n"
                        repeat with currentElement in allUIElementsInWindow
                            try
                                if role of currentElement is "AXGroup" then
                                    set elName to ""
                                    try set elName to name of currentElement end try
                                    set elDesc to ""
                                    try set elDesc to description of currentElement end try

                                    if (elName is "Status Bar") or (elDesc is "Status Bar") or (elName contains "Status Bar") or (elDesc contains "Status Bar") then
                                        set statusBarGroup to currentElement
                                        set uiDumpLog to uiDumpLog & "      Status Bar Group FOUND: Name='" & (elName default "[No Name]") & "', Desc='" & (elDesc default "[No Desc]") & "'\\n"
                                        exit repeat
                                    end if
                                end if
                            on error
                                -- ignore error inspecting one element during scan
                            end try
                        end repeat
                    on error err_find_sb_outer
                        set uiDumpLog to uiDumpLog & "    Error during comprehensive search for status bar group: " & err_find_sb_outer & "\\n"
                    end try

                    if statusBarGroup is not null then
                        set uiDumpLog to uiDumpLog & "    Status bar group located. Inspecting static texts...\\n"
                        try
                            set staticTextsInGroup to static texts of statusBarGroup
                            set uiDumpLog to uiDumpLog & "      Found " & (count of staticTextsInGroup) & " static text(s) in status bar group.\\n"
                            if (count of staticTextsInGroup) > 0 then
                                set stIndex to 0
                                repeat with st_item in staticTextsInGroup
                                    set stIndex to stIndex + 1
                                    set uiDumpLog to uiDumpLog & "        Static Text " & stIndex & ":\\n"
                                    set currentTextValue to "[No Value/Missing]"
                                    try
                                        set tempVal to value of st_item
                                        if tempVal is not missing value then set currentTextValue to tempVal as text
                                    on error err_val
                                        set currentTextValue to "[Error getting value: " & err_val & "]"
                                    end try
                                    set uiDumpLog to uiDumpLog & "          Value: '" & currentTextValue & "'\\n"
                                    
                                    set stName to "[No Name]"
                                    try set stName to name of st_item end try
                                    set uiDumpLog to uiDumpLog & "          Name: '" & stName & "'\\n"
                                    
                                    set stDesc to "[No Desc]"
                                    try set stDesc to description of st_item end try
                                    set uiDumpLog to uiDumpLog & "          Description: '" & stDesc & "'\\n"

                                    if currentTextValue is not "[No Value/Missing]" and currentTextValue is not "[Error getting value]" and currentTextValue contains "Gradle Build Running" then
                                        set uiDumpLog to uiDumpLog & "          >>> Gradle Build Running found! <<<\\n"
                                        set buildRunning to true
                                    end if
                                end repeat
                            else
                                set uiDumpLog to uiDumpLog & "      No static texts found in status bar group.\\n"
                            end if
                        on error err_read_st
                            set uiDumpLog to uiDumpLog & "    Error reading static texts from status bar group: " & err_read_st & "\\n"
                        end try
                    else
                        set uiDumpLog to uiDumpLog & "    Status bar group NOT found in this attempt.\\n"
                    end if
                end if -- targetWindow is not null
                
                if buildRunning then exit repeat -- Main polling loop exit if found
            end tell -- process
        end tell -- System Events
    on error err_main_try
        set uiDumpLog to uiDumpLog & "  Main AppleScript try block error in attempt " & checkAttempts & ": " & err_main_try & "\\n"
                    end try
    
    if buildRunning is false and checkAttempts < maxCheckAttempts then
        if targetWindow is null then
             set uiDumpLog to uiDumpLog & "  Target window was null, will delay and retry poll.\\n"
        end if
        set uiDumpLog to uiDumpLog & "  Build not (yet) detected as running. Delaying 0.5s before next check.\\n"
        delay 0.5
    end if
end repeat

if buildRunning is true then
    set uiDumpLog to uiDumpLog & "Build detected as running within " & checkAttempts & " attempt(s).\\n"
else
    set uiDumpLog to uiDumpLog & "Build NOT detected as running after " & checkAttempts & " attempt(s).\\n"
endif

set uiDumpLog to uiDumpLog & "[UI DUMP END]"
return "buildRunning:" & buildRunning & "|uiDump:" & uiDumpLog
APPLESCRIPT_EOF
)

    # Separate the build status from the UI dump
    build_status_boolean_line=$(echo "$raw_build_check_output" | cut -d'|' -f1)
    build_status_boolean=$(echo "$build_status_boolean_line" | cut -d':' -f2)
    
    ui_dump_details_line=$(echo "$raw_build_check_output" | cut -d'|' -f2-)
    ui_dump_details=$(echo "$ui_dump_details_line" | sed 's/^uiDump://')

    echo "[SCRIPT] ----- DETAILED UI DUMP FOR BUILD CHECK (Overall Attempt $attempt) -----"
    echo -e "${ui_dump_details}"
    echo "[SCRIPT] ----- END OF UI DUMP -----"

    echo "[SCRIPT] Parsed build running status: '$build_status_boolean'"
    if [ "$build_status_boolean" = "true" ]; then
      buildStarted=1
      echo "[SCRIPT] Build started successfully."
    else
      echo "[SCRIPT] Build did not start (based on UI check in overall attempt $attempt). Retrying overall process..."
      sleep 3
    fi
  else
    echo "[SCRIPT] Status bar not empty or not ready (content: '$status_bar_text'). Waiting..."
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

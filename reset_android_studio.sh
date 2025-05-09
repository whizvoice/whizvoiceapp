#!/bin/bash

#  to set up this script to work on mac
# Go to System Preferences > Security & Privacy > Accessibility
# Add Terminal (or iTerm2 if you're using that) to the list of allowed apps
# Add Android Studio to the list as well

# Quit Android Studio and handle confirmation dialogs
osascript <<EOF
set script_output to {} -- Initialize a list to hold log messages

tell application "Android Studio"
    try
        activate
        quit
    on error errMsg
        set end of script_output to ("Error trying to quit Android Studio application: " & errMsg)
    end try
end tell

delay 1 -- Initial delay for dialogs to potentially appear

tell application "System Events"
    if not (exists process "Android Studio") then
        set end of script_output to ("Android Studio process not found after quit command; assuming it closed or failed to start interaction.")
        -- Convert script_output to text and return
        set oldDelimiters to AppleScript's text item delimiters
        set AppleScript's text item delimiters to linefeed
        set outputText to script_output as text
        set AppleScript's text item delimiters to oldDelimiters
        return outputText
    end if

    -- If we reach here, process "Android Studio" existed moments ago.
    -- Now, try to interact with it for dialogs.
    try
        tell process "Android Studio"
            set handled_dialog_confirm_exit to false
            repeat 5 times
                if exists (window "Confirm Exit") then
                    try
                        tell window "Confirm Exit"
                            set num_buttons to count of buttons
                            if num_buttons > 0 then
                                click button num_buttons
                                set handled_dialog_confirm_exit to true
                                set end of script_output to ("Dialog 'Confirm Exit': Clicked button " & num_buttons & " (highest of " & num_buttons & ").")
                                exit repeat
                            else
                                set end of script_output to ("Dialog 'Confirm Exit': Found, but no buttons detected.")
                            end if
                        end tell
                    on error errMsg
                        set end of script_output to ("Dialog 'Confirm Exit': Error - " & errMsg)
                    end try
                end if
                if handled_dialog_confirm_exit then exit repeat
                -- If AS quit while we were trying to handle Confirm Exit, exit this loop
                if not (exists process "Android Studio") then exit repeat 
                delay 1
            end repeat

            delay 0.5

            -- Check again if process exists before trying next dialog
            if (exists process "Android Studio") then
                set handled_dialog_process_running to false
                repeat 5 times
                    if exists (window ("Process " & "'" & "app" & "'" & " Is Running")) then
                        try
                            tell window ("Process " & "'" & "app" & "'" & " Is Running")
                                set num_buttons to count of buttons
                                if num_buttons > 0 then
                                    click button num_buttons
                                    set handled_dialog_process_running to true
                                    set end of script_output to ("Dialog 'Process ''app'' Is Running': Clicked button " & num_buttons & " (highest of " & num_buttons & ").")
                                    exit repeat
                                else
                                    set end of script_output to ("Dialog 'Process ''app'' Is Running': Found, but no buttons detected.")
                                end if
                            end tell
                        on error errMsg
                            set end of script_output to ("Dialog 'Process ''app'' Is Running': Error - " & errMsg)
                        end try
                    end if
                    if handled_dialog_process_running then exit repeat
                    -- If AS quit while we were trying to handle Process Running, exit this loop
                    if not (exists process "Android Studio") then exit repeat 
                    delay 1
                end repeat
            end if
            
            -- Check again for the fallback
            if (exists process "Android Studio") then
                repeat 3 times
                    set fallback_clicked_button to false
                    try
                        repeat with w in windows
                            if (count of buttons of w) > 0 then
                                set num_buttons_fallback to count of buttons of w
                                click button num_buttons_fallback of w
                                set fallback_clicked_button to true
                                set end of script_output to ("Fallback: Clicked button " & num_buttons_fallback)
                                exit repeat
                            end if
                        end repeat
                    on error errMsg
                        set end of script_output to ("Fallback: Error - " & errMsg)
                    end try
                    if fallback_clicked_button then exit repeat
                    -- If AS quit during fallback, exit this loop
                    if not (exists process "Android Studio") then exit repeat
                    delay 1
                end repeat
            end if
        end tell -- process "Android Studio"
    on error errMsg number errNum
        if errNum is -1728 then -- errAENoSuchObject (process likely already quit)
            set end of script_output to ("Android Studio process terminated before or during dialog interaction (expected if it quit quickly).")
        else
            set end of script_output to ("Error telling Android Studio process: " & errMsg & " (Number: " & errNum & ")")
        end if
    end try
end tell

-- Convert script_output to text and return
set oldDelimiters to AppleScript's text item delimiters
set AppleScript's text item delimiters to linefeed
set outputText to script_output as text
set AppleScript's text item delimiters to oldDelimiters
return outputText
EOF

# Wait for Android Studio to fully quit
sleep 2

# Reopen Android Studio with current project
open -a "Android Studio" .

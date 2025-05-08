#!/bin/bash

#  to set up this script to work on mac
# Go to System Preferences > Security & Privacy > Accessibility
# Add Terminal (or iTerm2 if you're using that) to the list of allowed apps
# Add Android Studio to the list as well

LOGFILE="/tmp/android_studio_dialog_buttons.log"
rm -f "$LOGFILE"

# Quit Android Studio and handle confirmation dialogs
osascript <<EOF
set logFile to POSIX file "$LOGFILE"
tell application "Android Studio"
    activate
    quit
    delay 2
    tell application "System Events"
        repeat 10 times
            if exists (process "Android Studio") then
                tell process "Android Studio"
                    repeat with w in windows
                        try
                            set btns to buttons of w
                            set btnNames to {}
                            repeat with b in btns
                                set end of btnNames to name of b
                            end repeat
                            set fileRef to open for access logFile with write permission
                            write ("Button names: " & btnNames & linefeed) to fileRef starting at eof
                            close access fileRef
                            if (count of btns) > 0 then
                                click item (count of btns) of btns
                            end if
                        end try
                    end repeat
                end tell
            end if
            delay 1
        end repeat
    end tell
end tell
EOF

# Print the log file contents
cat "$LOGFILE"

# Wait for Android Studio to fully quit
sleep 2

# Reopen Android Studio with current project
open -a "Android Studio" .

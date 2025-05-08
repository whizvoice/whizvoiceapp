#!/bin/bash

# Function to handle Android Studio quit with confirmation dialogs
quit_android_studio() {
    osascript <<EOF
    tell application "Android Studio"
        activate
        delay 1
        tell application "System Events"
            # Try to quit normally first
            keystroke "q" using {command down}
            delay 1
            
            # Handle "Do you want to terminate the running process?" dialog
            if exists (window 1 of process "Android Studio") then
                if exists (button "Terminate" of window 1 of process "Android Studio") then
                    click button "Terminate" of window 1 of process "Android Studio"
                end if
            end if
            
            # Handle "Do you want to save changes?" dialog
            if exists (window 1 of process "Android Studio") then
                if exists (button "Don't Save" of window 1 of process "Android Studio") then
                    click button "Don't Save" of window 1 of process "Android Studio"
                end if
            end if
        end tell
    end tell
EOF
}

# Quit Android Studio with dialog handling
quit_android_studio

# Wait a moment for Android Studio to fully quit
sleep 2

# Reopen Android Studio with current project
open -a "Android Studio" .

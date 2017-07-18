#!/usr/bin/env bash

cd "`dirname $0`"

die () {
	set +x # Turn off printing commands
	echo ""
	echo " *** fatal error: $*"
	exit 1
}

if [ -z $ANDROID_BUILD_TOP ]; then
  echo "You need to source and lunch before you can use this script"
  exit 1
fi

adb wait-for-device || die

echo "Running form command test. . ."
sleep 2

# Clobber any existing instance of wpantund
adb shell killall wpantund 2> /dev/null

# Start wpantund
echo "+ adb shell wpantund -s 'system:ot-ncp\ 1' -o Config:Daemon:ExternalNetifManagement 1 &"
adb shell wpantund -s 'system:ot-ncp\ 1' -o Config:Daemon:ExternalNetifManagement 1 &
WPANTUND_PID=$!
trap "kill -HUP $WPANTUND_PID 2> /dev/null" EXIT INT TERM

# Verify wpantund started properly
sleep 2
kill -0 $WPANTUND_PID || die "wpantund failed to start"
sleep 2

echo "+ adb shell lowpanctl status"
adb shell lowpanctl status || die
echo "+ adb shell lowpanctl form blahnet"
adb shell lowpanctl form blahnet || die
echo "+ adb shell lowpanctl status"
adb shell lowpanctl status || die
echo "+ adb shell ifconfig wpan0"
adb shell ifconfig wpan0 || die
echo "+ adb shell ip rule"
adb shell ip rule || die

echo "Finished form command test."


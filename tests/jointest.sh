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

echo "Running join command test. . ."
sleep 2

adb shell killall wpantund 2> /dev/null

adb shell wpantund -I wpan0 -s 'system:ot-ncp\ 1' -o Config:Daemon:ExternalNetifManagement 1 &
WPANTUND_1_PID=$!
adb shell wpantund -I wpan1 -s 'system:ot-ncp\ 2' -o Config:Daemon:ExternalNetifManagement 1 &
WPANTUND_2_PID=$!
trap "kill -HUP $WPANTUND_1_PID $WPANTUND_2_PID 2> /dev/null" EXIT INT TERM

sleep 2

kill -0 $WPANTUND_1_PID  || die "wpantund failed to start"
kill -0 $WPANTUND_2_PID  || die "wpantund failed to start"

sleep 2

echo "+ adb shell lowpanctl -I wpan0 status"
adb shell lowpanctl -I wpan0 status || die
echo "+ adb shell lowpanctl -I wpan0 form blahnet --panid 1234 --xpanid 0011223344556677 --channel 11"
adb shell lowpanctl -I wpan0 form blahnet --panid 1234 --xpanid 0011223344556677 --channel 11 || die
echo "+ adb shell lowpanctl -I wpan0 status"
adb shell lowpanctl -I wpan0 status || die
echo "+ adb shell lowpanctl -I wpan0 show-credential"
adb shell lowpanctl -I wpan0 show-credential || die

CREDENTIAL=`adb shell lowpanctl -I wpan0 show-credential -r` || die

echo "+ adb shell lowpanctl -I wpan1 status"
adb shell lowpanctl -I wpan1 status || die
echo "+ adb shell lowpanctl -I wpan1 scan"
adb shell lowpanctl -I wpan1 scan || die
echo "+ adb shell lowpanctl -I wpan1 join blahnet --panid 1234 --xpanid 0011223344556677 --channel 11 --master-key ${CREDENTIAL}"
adb shell lowpanctl -I wpan1 join blahnet --panid 1234 --xpanid 0011223344556677 --channel 11 --master-key ${CREDENTIAL} || die
echo "+ adb shell lowpanctl -I wpan1 status"
adb shell lowpanctl -I wpan1 status || die

echo "Finished join command test."

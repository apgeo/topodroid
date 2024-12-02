ANT    = /home/programs/apache-ant-1.8.2/bin/ant
CC     = gcc
CPLUS  = g++
AR     = ar
CFLAGS = -g -O0 -Wall -DMAIN

# verbose flag for ANT
AFLAGS = -v 

VERSION = `grep versionName AndroidManifest.xml | sed -e 's/ *android:versionName=//' | sed -e 's/"//g' `
TARGET_SDK = `grep targetSdkVersion AndroidManifest.xml | sed -e 's/ *android:targetSdkVersion=//' | sed -e 's/"//g' `
VERSION_CODE = `grep versionName AndroidManifest.xml | sed -e 's/ *android:versionCode=//' | sed -e 's/"//g' `

# APPCODE must coincide with the main source folder
APPCODE = TDX
# APPNAME is the application name, as in build.xml
APPNAME = TopoDroidX
APPVERSION = $(APPNAME)-$(VERSION)
LOGNAME = topodroid-X
PACKAGE = com.topodroid.$(APPCODE)
VERSION_TARGET = $(VERSION)-$(TARGET_SDK)

LANGS := cn fr hu it pt ru

default: debug-signed

debug:
	$(ANT) debug

release:
	$(ANT) release
	@mv bin/$(APPNAME)-release.apk $(APPVERSION)-release.apk
	@ls -l $(APPVERSION)-release.apk
	@md5sum $(APPVERSION)-release.apk

signed:
	$(ANT) release
	@echo "Version $(VERSION)"
	./howto/sign.sh
	@mv $(APPNAME)-release-keysigned.apk $(APPVERSION)-$(TARGET_SDK).apk
	@rm $(APPNAME)-release-keysigned.apk.idsig

md5:
	@echo "Version $(VERSION) target $(TARGET_SDK)"
	@./howto/update_md5.sh $(VERSION_TARGET) $(VERSION_CODE)

signed-29:
	./howto/target.sh 29
	make signed

signed-30:
	./howto/target.sh 30
	make signed

signed-31:
	./howto/target.sh 31
	make signed

debug-signed:
	$(ANT) debug
	./howto/sign-debug.sh

perms:
	adb shell appops set $(PACKAGE) READ_EXTERNAL_STORAGE allow
	adb shell appops set $(PACKAGE) WRITE_EXTERNAL_STORAGE allow
	adb shell appops set $(PACKAGE) MANAGE_EXTERNAL_STORAGE allow

# interactively it could be
#	adb shell if [[ `getprop ro.vendor.build.version.sdk` > 29 ]]; then appops set $(PACKAGE) MANAGE_EXTERNAL_STORAGE allow ; fi

bundle:
	$(ANT) release
	./howto/bundle.sh

install-nosigned:
	adb install -r bin/$(APPNAME)-debug.apk

install:
	adb install -r bin/$(APPNAME)-debug-keysigned.apk

uninstall:
	adb uninstall $(PACKAGE)

reinstall:
	adb uninstall $(PACKAGE)
	adb install -r bin/$(APPNAME)-debug-keysigned.apk

rebuild:
	$(ANT) clean
	$(ANT) debug

less:
	$(ANT) debug 2>&1 | less

lint:
	../../../cmdline-tools/latest/bin/lint --ignore IconLocation --ignore ObsoleteLayoutParam . > lint.out

strings:
	@ for i in $(LANGS); do echo "$$i" `grep \"$$i\" lint.out | wc -l`; done
	@ for i in $(LANGS); do echo "values-$$i" `grep values-$$i lint.out | wc -l`; done

strings_check:
	@ for i in $(LANGS); do utils/strings_compare.pl $$i 1 > int18/out/$$i.out ; done

pdf:
	./howto/pdf.sh

manual:
	./howto/pdf.sh

clean:
	$(ANT) clean

bclean:
	rm -rf bbin

symbols:
	./howto/symbols.sh

log:
	adb logcat | grep $(LOGNAME)

git-add:
	yes | git add -p

git-pull:
	git pull

help:
	@echo "Actions: [default] install clean lint, signed md5 pdf symbols, git-add git-pull, log perms"


SRC = \
  ./AndroidManifest.xml \
  ./ant/* \
  ./build.xml \
  ./LICENSE \
  ./COPYING \
  ./Makefile \
  ./proguard.cfg \
  ./project.properties \
  ./README.md \
  ./assets/*/* \
  ./firmware/* \
  ./int18/values-??/* \
  ./int18/howto \
  ./int18/readme \
  ./res/values/* \
  ./res/values-normal/* \
  ./res/values-small/* \
  ./res/values-large/* \
  ./res/layout/* \
  ./res/drawable/* \
  ./res/mipmap-*/* \
  ./res/raw/* \
  ./res/xml/* \
  ./symbols-git/*/*/* \
  ./howto/* \
  ./utils/* \
  ./unused/idea/* \
  ./unused/TopoDroid-icon/* \
  ./src/com/topodroid/c3db/*.java \
  ./src/com/topodroid/c3in/*.java \
  ./src/com/topodroid/c3out/*.java \
  ./src/com/topodroid/c3walls/*/*.java \
  ./src/com/topodroid/common/*.java \
  ./src/com/topodroid/calib/*.java \
  ./src/com/topodroid/dev/*.java \
  ./src/com/topodroid/dev/*/*.java \
  ./src/com/topodroid/$(APPCODE)/*.java \
  ./src/com/topodroid/dln/*.java \
  ./src/com/topodroid/help/*.java \
  ./src/com/topodroid/inport/*.java \
  ./src/com/topodroid/io/*/*.java \
  ./src/com/topodroid/mag/*.java \
  ./src/com/topodroid/math/*.java \
  ./src/com/topodroid/num/*.java \
  ./src/com/topodroid/packetX/*.java \
  ./src/com/topodroid/prefs/*.java \
  ./src/com/topodroid/ptopo/*.java \
  ./src/com/topodroid/tdm/*.java \
  ./src/com/topodroid/trb/*.java \
  ./src/com/topodroid/ui/*.java \
  ./src/com/topodroid/utils/*.java

EXTRA_SRC = \
  ./studio/* \
  ./save/* 

version:
	echo $(VERSION)

archive:
	tar -chvzf ../topodroid-`date -I`.tgz --exclude-vcs $(SRC)

release-archive:
	tar -chvzf ../topodroidX-`date -I`.tgz --exclude-vcs $(SRC) $(APPVERSION)-$(TARGET_SDK).apk

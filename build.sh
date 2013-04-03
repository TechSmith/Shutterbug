#!/bin/bash -xe

echo "Building Shutterbug"
android update lib-project --target android-16 --path .
ant clean
ant release


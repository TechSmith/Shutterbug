#!/bin/bash -xe

echo "Building Shutterbug"
android update lib-project --target android-17 --path .
ant clean
ant release


#!bin/bash
javac -d ./build -cp ".:./lib/Jackson/*:./lib/CommonsApache/*:" MainServer.java
java -cp ".:./build:./lib/Jackson/*:./lib/CommonsApache/*:" MainServer "$@"
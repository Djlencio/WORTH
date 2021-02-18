#!bin/bash
javac -d ./build -cp ".:./lib/CommonsApache/*:" MainClient.java
java -cp ".:./build:./lib/CommonsApache/*:" MainClient "$@"
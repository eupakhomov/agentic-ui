#!/usr/bin/env bash
# Throwaway file for testing "@bot review <PR>" from Slack. Safe to delete; this PR will be closed.

NAME=$1
GREETING="hello $NAME"
echo $GREETING
cd $NAME
ls

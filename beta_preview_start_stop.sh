#!/bin/bash
#/var/lib/jenkins/ES_Beta_Preview_Mgmt/beta_preview_start_stop.sh
arg1="$1"
sites=`aws ec2 describe-instances --profile es --filter 'Name=tag:Name,Values=beta*,preview*' --query 'Reservations[*].{xa:Instances[].Tags[?Key==\`Name\`].Value | [0]|[0],xb:Instances[].InstanceId | [0], state:Instances[].State.Name | [0]}' --output text|sort -r`
beta_sites=`echo "$sites"|grep beta|awk '{print "<tr><td>",$2,"</td><td><input type=checkbox ",$1," name=value><input type=hidden name=value value=\"",$3,"\"></td></tr>"}'`
preview_sites=`echo "$sites"|grep preview|awk '{print "<tr><td>",$2,"</td><td><input type=checkbox ",$1," name=value><input type=hidden name=value value=\"",$3,"\"></td></tr>"}'`
if [ "$arg1" == "all" ];then
echo "<TABLE><TR><TD WIDTH=20>&nbsp;</TD><TD><TABLE>${beta_sites}</TABLE></TD><TD width=100>&nbsp;</TD><TD><TABLE>${preview_sites}</TABLE></TD></TR></TABLE>" | sed 's/running/checked/g' | sed 's/stopped/checked/g'
elif [ "$arg1" == "none" ];then
echo "<TABLE><TR><TD WIDTH=20>&nbsp;</TD><TD><TABLE>${beta_sites}</TABLE></TD><TD width=100>&nbsp;</TD><TD><TABLE>${preview_sites}</TABLE></TD></TR></TABLE>"
else
echo "<TABLE><TR><TD WIDTH=20>&nbsp;</TD><TD><TABLE>${beta_sites}</TABLE></TD><TD width=100>&nbsp;</TD><TD><TABLE>${preview_sites}</TABLE></TD></TR></TABLE>" | sed 's/running/checked/g'
fi

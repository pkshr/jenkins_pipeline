node {
  started_instances=[]
  count_stopped_instances=0
  count_started_instances=0
  max_wait_time_till_instance_is_running=120 //seconds
  wait_interval_in_seconds = 30 //seconds
  json_str_instance_target_states="["
  json_str_instance_current_states=""
  instances=""
  map_cur_states = [:]
  map_cur_names = [:]  
  list_instances_to_start = []
  dry_run=" --dry-run"
  email_recipient = "pawan.shrestha@uplight.com"
  
  if(params.Confirm !="false"){
    dry_run=""
  }

  hosted_zone_id='Z1M7PX172PSCHE'
  stage('Get instance & state (Current vs Target)'){
      def arrInstances = params.Check_For_Start.split(',') 
      status_counter=0
      instance_counter=1
      for(i=0;i<arrInstances.length/2;i++){
          new_state=(arrInstances[status_counter]=="false")?"stopped":"running"
          instance_id=arrInstances[instance_counter].trim()
          if(i>0){
            instances=instances+" "
            json_str_instance_target_states=json_str_instance_target_states+","
          }
          
          instances=instances+instance_id
          json_str_instance_target_states=json_str_instance_target_states+'{"instance_id":"'+instance_id+'", "state":"'+new_state+'"}'

          status_counter+=2
          instance_counter+=2
      }
      json_str_instance_target_states=json_str_instance_target_states+']'
      json_str_instance_current_states = sh(script:"aws ec2 describe-instances --profile es --instance-id ${instances} --query 'Reservations[*].{instance_id:Instances[].InstanceId | [0], instance_name:Instances[].Tags[?Key==`Name`].Value |[0] | [0], state:Instances[].State.Name | [0]}'", returnStdout: true)
  
      def json_current = readJSON text: json_str_instance_current_states
      json_current.each{
        map_cur_states["${it.instance_id}"]="${it.state}"
        map_cur_names["${it.instance_id}"]="${it.instance_name}"
      }  
  }

  instances_to_start = ""
  instances_to_stop = ""

  stage('Change instance state (Current to Target)'){
    def json_target = readJSON text: json_str_instance_target_states

    json_target.each{
      cur_name=map_cur_names["${it.instance_id}"]
      cur_state=map_cur_states["${it.instance_id}"]

      if ("${it.state}" != "${cur_state}"){
        echo "INFO: state of ${cur_name} from (${cur_state}) to (${it.state})"
        if ("${it.state}" == "running"){
          list_instances_to_start << "${it.instance_id}"
          instances_to_start="${it.instance_id} ${instances_to_start}"
        }
        else{
          instances_to_stop="${it.instance_id} ${instances_to_stop}"
        }
      }
      else{
        echo "INFO: no state change for ${cur_name} (${it.state})"
      }
    }

    if(instances_to_start!=""){
      echo "CHANGE: Following instances will be started ${instances_to_start}"
      aws_out=sh(script:"aws ec2 start-instances --profile es --instance-ids ${instances_to_start}${dry_run}", returnStdout: true).trim()
      echo aws_out
    }
    if(instances_to_stop!=""){
      echo "CHANGE: Following instances will be stopped ${instances_to_stop}"
      aws_out=sh(script:"aws ec2 stop-instances --profile es --instance-ids ${instances_to_stop}${dry_run}", returnStdout: true).trim()
      echo aws_out
    }
  }

  stage('Backup DNS Record'){
    if(instances_to_start!=""){
      dns_record = sh(script:"aws route53 list-resource-record-sets --hosted-zone-id ${hosted_zone_id} --profile es --query \"ResourceRecordSets[?Type=='CNAME']\"", returnStdout: true).trim()
      echo "INFO: DNS records ${dns_record}"
    }
    else{
      echo "INFO: No new instances are set to start"
    }
  }

  max_wait_time_till_instance_is_running=120 //seconds
  wait_interval_in_seconds = 15 //seconds
  stage('Update DNS Record'){
    echo "Total ${list_instances_to_start.size()} record(s) need to be updated"
    list_instances_to_start.each{
      echo "INFO: Update record for ${map_cur_names[it]}.evoworx.org"

      //instance may not be completely started so we have to wait till instance is started
      instance_state = "stopped"
      wait_time = 0
      while(wait_time<max_wait_time_till_instance_is_running) {
        instance_state = sh(script:"aws ec2 describe-instances --profile es --instance-id ${it} --query 'Reservations[*].Instances[].State.Name' --output text", returnStdout: true).trim()
        if (instance_state == "running"){
          wait_time=max_wait_time_till_instance_is_running
        }
        else{
          wait_time=wait_time+wait_interval_in_seconds
        }
        echo "INFO: Instance not yet started. Waiting ${wait_interval_in_seconds} seconds now"
        sleep(wait_interval_in_seconds)
      }

      //if instance is now running
      if(instance_state == "running"){
        public_dns = sh(script:"aws ec2 describe-instances --profile es --instance-id ${it} --query 'Reservations[*].Instances[].PublicDnsName' --output text", returnStdout: true).trim()
        echo "CHANGE: DNS record for (${map_cur_names[it]}.evoworx.org) updated to (${public_dns})"

        dns_record="""
        {
        "Comment": "Update record for ${map_cur_names[it]}.evoworx.org",
        "Changes": [
          {
            "Action": "UPSERT",
            "ResourceRecordSet": {
              "Name": "${map_cur_names[it]}.evoworx.org.",
              "Type": "CNAME",
              "TTL": 300,
              "ResourceRecords": [
                {
                  "Value": "${public_dns}"
                }
              ]
            }
          }]
        }       
        """
        route53_out=sh(script:"aws route53 change-resource-record-sets --profile es --hosted-zone-id Z1M7PX172PSCHE --change-batch '${dns_record}'", returnStdout: true).trim()
        echo "INFO: ${route53_out}"
      }
      else{
        echo "ERROR: Instance ${it} (${map_cur_names[it]}) did not start within ${max_wait_time_till_instance_is_running} seconds"
      }      
    }
  }

  stage('Email notification'){
    build_user=""
    wrap([$class: 'BuildUser']) {
      build_user = env.BUILD_USER_ID
    }
  
    html_body=sh(script:"/var/lib/jenkins/ES_Beta_Preview_Mgmt/beta_preview_start_stop.sh", returnStdout: true).trim()  
    emailext (
    mimeType: 'text/html',
    subject: "EnergySavvy: ${env.JOB_NAME} ${env.BUILD_NUMBER}",
    body: """<p>Site startup/shutdown job [<a href="${env.BUILD_URL}">${env.JOB_NAME}</a>] was executed by ${build_user}. <BR/><BR/>Below is status of current beta/preview sites. <BR/><input type=checkbox checked>Running <input type=checkbox>Stopped<hr/></p> ${html_body}""",
    to: "${email_recipient}"
    )
  }  
}

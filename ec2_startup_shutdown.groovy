node {
  started_instances=[]
  count_stopped_instances=0
  count_started_instances=0
  max_wait_time_till_instance_is_running=120 //seconds
  wait_interval_in_seconds = 30 //seconds

    stage('Instance startup/shutdown'){
        def arrInstances = params.Check_For_Start.split(',') 
        status_counter=0
        instance_counter=1
        for(i=0;i<arrInstances.length/2;i++){
            new_state=(arrInstances[status_counter]=="false")?"stopped":"running"
            instance=arrInstances[instance_counter]
            status_counter+=2
            instance_counter+=2
            aws_out = sh(script:"aws ec2 describe-instances --profile es --instance-id ${instance} --query 'Reservations[*].{instance_name:Instances[].Tags[?Key==`Name`].Value |[0] | [0], state:Instances[].State.Name | [0]}'", returnStdout: true)
            def jsonObj = readJSON text: aws_out
            cur_state="${jsonObj[0].state}".trim()
            instance_name="${jsonObj[0].instance_name}".trim()
            echo "INFO: ${instance_name} (${instance}) has current state as [$cur_state], new state wanted is [$new_state]"
            if (cur_state != new_state){
                if(new_state == "stopped"){
                    echo "CHANGE: ${instance_name} (${instance}) state change from $cur_state to $new_state"
                    count_stopped_instances=count_stopped_instances+1
                }
                else if(new_state == "running"){
                    echo "CHANGE: ${instance_name} (${instance}) state change from ${cur_state} to ${new_state}"
                    count_started_instances=count_started_instances+1
                    started_instances << "${instance}"
                }
            }
        }
    }

    stage('Update DNS record'){
      if(count_started_instances>0){
            started_instances.each{
                instance="${it}"

                //instance may not be completely started so we have to wait till instance is started

                instance_state = "stopped"
                wait_time = 0
                while(wait_time<max_wait_time_till_instance_is_running) {
                  instance_state = sh(script:"aws ec2 describe-instances --profile es --instance-id ${instance} --query 'Reservations[*].Instances[].State.Name' --output text", returnStdout: true).trim()
                  if (instance_state == "running"){
                    wait_time=max_wait_time_till_instance_is_running
                  }
                  else{
                    wait_time=wait_time+wait_interval_in_seconds
                  }
                  sleep(wait_interval_in_seconds)
                }

                aws_out = sh(script:"aws ec2 describe-instances --profile es --instance-id ${instance} --query 'Reservations[*].{instance_name:Instances[].Tags[?Key==`Name`].Value|[0] | [0], public_dns:Instances[].PublicDnsName| [0]}' ", returnStdout: true)
                def jsonObj = readJSON text: aws_out
                public_dns="${jsonObj[0].public_dns}".trim()
                instance_name="${jsonObj[0].instance_name}".trim()

                if(instance_state=="running"){
                  echo "CHANGE: Domain record updated for ${instance_name}.evoworx.org to [${public_dns}]"
                }
                else{
                  echo "ERROR: ${instance_name} did not come up in time. Domain record not updated"
                }
          }
      }
      else{
          echo "INFO: Nothing to do. No instances started from stopped state"
      }
    }
}

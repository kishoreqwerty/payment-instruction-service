#!/usr/bin/env bash
# Lists AWS resources tagged for this benchmark that survive after
# `terraform destroy`. Terraform only knows about what it created directly
# -- a controller running inside the cluster (the AWS Load Balancer
# Controller, in particular) can create an ALB or a Target Group on its
# own, and that resource has no Terraform state entry to be destroyed by.
# This is the check that catches it, not a mechanism that prevents it.
set -euo pipefail

region="${AWS_REGION:-us-east-2}"
project_tag="payment-instruction-service"

echo "== Checking for surviving resources tagged Project=${project_tag} in ${region} =="

echo
echo "-- Load balancers --"
aws elbv2 describe-load-balancers --region "$region" \
  --query "LoadBalancers[].LoadBalancerArn" --output text 2>/dev/null | tr '\t' '\n' | while read -r arn; do
    [ -z "$arn" ] && continue
    tags=$(aws elbv2 describe-tags --region "$region" --resource-arns "$arn" \
      --query "TagDescriptions[0].Tags[?Key=='Project'].Value" --output text 2>/dev/null)
    if [ "$tags" = "$project_tag" ]; then
      echo "SURVIVING: load balancer $arn"
    fi
  done

echo
echo "-- EBS volumes (unattached) --"
aws ec2 describe-volumes --region "$region" \
  --filters "Name=status,Values=available" "Name=tag:Project,Values=${project_tag}" \
  --query "Volumes[].VolumeId" --output text 2>/dev/null | tr '\t' '\n' | while read -r vol; do
    [ -z "$vol" ] && continue
    echo "SURVIVING: unattached EBS volume $vol"
  done

echo
echo "-- Elastic IPs --"
aws ec2 describe-addresses --region "$region" \
  --filters "Name=tag:Project,Values=${project_tag}" \
  --query "Addresses[].PublicIp" --output text 2>/dev/null | tr '\t' '\n' | while read -r ip; do
    [ -z "$ip" ] && continue
    echo "SURVIVING: Elastic IP $ip"
  done

echo
echo "-- NAT gateways --"
aws ec2 describe-nat-gateways --region "$region" \
  --filter "Name=tag:Project,Values=${project_tag}" "Name=state,Values=available,pending" \
  --query "NatGateways[].NatGatewayId" --output text 2>/dev/null | tr '\t' '\n' | while read -r nat; do
    [ -z "$nat" ] && continue
    echo "SURVIVING: NAT gateway $nat"
  done

echo
echo "-- EKS clusters --"
aws eks list-clusters --region "$region" --query "clusters" --output text 2>/dev/null | tr '\t' '\n' | while read -r c; do
    [ -z "$c" ] && continue
    case "$c" in
      payments-bench*) echo "SURVIVING: EKS cluster $c" ;;
    esac
  done

echo
echo "-- RDS instances --"
aws rds describe-db-instances --region "$region" \
  --query "DBInstances[].DBInstanceIdentifier" --output text 2>/dev/null | tr '\t' '\n' | while read -r db; do
    [ -z "$db" ] && continue
    case "$db" in
      payments-bench*) echo "SURVIVING: RDS instance $db" ;;
    esac
  done

echo
echo "== Done. Any 'SURVIVING' line above is a billable resource terraform destroy did not remove -- investigate and delete it by hand. =="

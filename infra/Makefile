.PHONY: init plan apply destroy verify-destroy

init:
	terraform init

plan:
	terraform plan

apply:
	terraform apply

# The phase's own non-negotiable first step: verify a throwaway minimal
# apply destroys cleanly BEFORE anything expensive exists. Not a
# substitute for reading terraform plan's output yourself.
verify-destroy:
	terraform apply -target=module.network -auto-approve
	terraform destroy -target=module.network -auto-approve
	./scripts/post-destroy-check.sh

# Tears down everything Terraform manages, then checks for anything it
# doesn't (load-balancer-controller-created ALBs, orphaned EBS volumes,
# Elastic IPs) so "destroyed" means destroyed, not "terraform thinks it
# destroyed."
destroy:
	terraform destroy -auto-approve
	./scripts/post-destroy-check.sh

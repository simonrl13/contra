# Deploying contra-api to AWS: the plan

**Status: prepared, not deployed.** No AWS resource has been created, and no AWS account or credentials
were used to write this. Everything here is a plan plus templates that pass `cfn-lint` 1.61 (offline
validation against the CloudFormation schema). They have **not** been run against AWS, so expect to
fix small things on the first real deploy.

Files:
| File | What it creates |
|---|---|
| `infra/budget.yml` | A monthly cost budget with email alerts. Create it **first**. |
| `infra/app.yml` | VPC, security groups, RDS PostgreSQL 16, ECR repository, log group, ALB, ECS cluster, task definition, service, task execution role |
| `infra/github-oidc.yml` | GitHub OIDC provider + a deploy role that one repository can assume (optional, for CI deploys) |
| `infra/deploy-workflow.yml` | GitHub Actions deploy workflow, **inactive** until copied to `.github/workflows/` |

## 1. What was checked before writing this (2026-11-24)
| Question | Finding | Source |
|---|---|---|
| Is App Runner still an option? | **No.** "AWS App Runner is no longer open to new customers… If you're not an existing customer, AWS recommends Amazon ECS Express Mode." (closed to new customers since 30 April 2026) | [App Runner docs](https://docs.aws.amazon.com/apprunner/latest/dg/what-is-apprunner.html) |
| What is ECS Express Mode? | Needs an image, a task execution role and an infrastructure role. It creates a Fargate service, a (shared) ALB with TLS and a URL, autoscaling and monitoring. No extra charge beyond the underlying resources. | [ECS Express Mode docs](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/express-service-overview.html) |
| Is PostgreSQL 16 supported on RDS? | Yes. RDS end of standard support: **28 February 2029**. Latest minor on the calendar: 16.15. | [RDS PostgreSQL release calendar](https://docs.aws.amazon.com/AmazonRDS/latest/PostgreSQLReleaseNotes/postgresql-release-calendar.html) |
| Free tier for a new account | Accounts created since 15 July 2025 get credits ($100, plus up to $100 more for activities) instead of 12 months of free services. A "Free plan" account closes after 6 months or when the credits run out. | [AWS Free Tier](https://aws.amazon.com/free/), summarised by [AWS Builder Center](https://builder.aws.com/content/3Iv9tJEEumjb9aFubl1zmd53SlP/the-aws-free-tier-changed-what-every-new-account-gets-in-2026-and-the-five-things-to-set-up-on-day-one) (check the official page for your account) |
| Budgets cost? | Budgets without actions are free. | [AWS Budgets pricing](https://aws.amazon.com/aws-cost-management/aws-budgets/pricing/) |

## 2. Architecture
```
 you (AllowedCidr only)
        │ HTTP :80
        ▼
 Application Load Balancer ── public subnets A, B
        │ :8080, health check GET /actuator/health
        ▼
 ECS Fargate task (0.5 vCPU, 1 GB) ── public subnet, public IP, SG: only from the ALB
        │ :5432, sslmode=require
        ▼
 RDS PostgreSQL 16.15, db.t4g.micro, 20 GB gp3, encrypted ── private subnets, SG: only from the app

 ECS pulls the image from ECR, reads DB_USERNAME/DB_PASSWORD from the RDS-managed secret in
 Secrets Manager, and sends logs to CloudWatch Logs (/ecs/contra-api, kept 7 days).
```

### Why ECS Fargate + ALB, written as CloudFormation
- **App Runner** is closed to new customers (see above).
- **ECS Express Mode** is AWS's suggested replacement and would give HTTPS and a URL with very little
  config. Not chosen as the main path because it creates the networking and load balancer for you, from a
  broad "infrastructure role". For an interview, a template where every security group and IAM
  statement is visible and explainable is worth more. It's the best alternative if you want HTTPS without
  owning a domain.
- **EC2 or Elastic Beanstalk**: more to patch and operate. **EKS**: far too much for one service.
- **CloudFormation vs Terraform**: CloudFormation needs no extra tool or state bucket and can be
  checked offline with `cfn-lint`. Terraform is more common in job ads. Both express the same resources.

### Cost decisions
- **No NAT gateway.** Tasks run in public subnets with a public IP, so they can reach ECR and Secrets
  Manager directly. Their security group accepts only the ALB. A NAT gateway costs more than the whole
  rest of the stack at this size. The trade-off: in a real system, tasks would sit in private subnets
  behind a NAT gateway or VPC endpoints.
- **Single-AZ database, one task, x86.** Not highly available. Multi-AZ RDS roughly doubles the
  database cost. ARM (Graviton) Fargate is ~20% cheaper but needs an arm64 image build; x86 keeps the
  build identical to CI.

## 3. Cost estimate (us-east-1, on-demand, 730 hours/month)
| Item | Rate | Source | Per month |
|---|---|---|---|
| Fargate x86 0.5 vCPU | $0.000011244 / vCPU-second (≈ $0.0405 / vCPU-hour) | [Fargate pricing](https://aws.amazon.com/fargate/pricing/) | $14.77 |
| Fargate 1 GB memory | $0.000001235 / GB-second (≈ $0.00445 / GB-hour) | same | $3.25 |
| ALB | $0.0225 / hour + $0.008 / LCU-hour (≈ 0.1 LCU idle demo) | [ELB pricing](https://aws.amazon.com/elasticloadbalancing/pricing/) | $16.43 + ~$0.60 |
| Public IPv4 (2 for the ALB, 1 for the task) | $0.005 / IP-hour | [VPC pricing](https://aws.amazon.com/vpc/pricing/) | $10.95 |
| RDS db.t4g.micro PostgreSQL, Single-AZ | $0.016 / hour | ⚠ secondary source ([CloudRoute, citing the AWS Price List of 2026-11-13](https://www.cloudroutehq.com/aws/rds-pricing)): the official table didn't render | $11.68 |
| RDS gp3 storage 20 GB | $0.115 / GB-month | ⚠ same secondary source | $2.30 |
| Secrets Manager (1 RDS-managed secret) | $0.40 / secret-month + $0.05 / 10,000 calls | [Secrets Manager pricing](https://aws.amazon.com/secrets-manager/pricing/) | $0.40 |
| CloudWatch Logs, ECR storage, data transfer | small at demo volume | not looked up | ~$1 |
| **Total if left running** | | | **≈ $61 / month ≈ $2 / day ≈ $0.08 / hour** |

- **A demo session** (deploy, test, tear down within a few hours) costs well under $1.
- **Forgetting to tear down** costs ~$61/month. That's why the budget comes first: the forecast alert
  at $20 fires within the first days.
- Before deploying, put these numbers into the [AWS Pricing Calculator](https://calculator.aws/) for
  your region. The RDS figures especially are not from an official AWS page. São Paulo (sa-east-1)
  is closer to you but more expensive than us-east-1. New-account credits would cover a demo.

## 4. IAM: least privilege, no long-lived keys
Four kinds of identity, and none of them is an access key:

| Who | How it authenticates | What it may do |
|---|---|---|
| You, deploying stacks | IAM Identity Center user + `aws sso login` (temporary credentials) | Create/delete these stacks. Never create IAM user access keys; MFA on root and on your user. |
| ECS, starting the task (**task execution role**, `infra/app.yml`) | ECS assumes it (`ecs-tasks.amazonaws.com`, only for this account) | Pull from **this** ECR repository, write to **this** log group, read **this one** secret. `ecr:GetAuthorizationToken` needs `Resource: "*"` (AWS doesn't support narrowing it). |
| The running app (**task role**) | none: there is no task role | Nothing. The app calls no AWS API, so it gets no AWS permissions at all. |
| GitHub Actions (**deploy role**, `infra/github-oidc.yml`) | OIDC: GitHub signs a short-lived token, AWS exchanges it for 1-hour credentials | Push to this ECR repository, register task definitions, update **this** service, pass **only** the execution role and only to ECS. |

The deploy role's trust policy is the important part:
```yaml
Condition:
  StringEquals:
    "token.actions.githubusercontent.com:aud": sts.amazonaws.com
    "token.actions.githubusercontent.com:sub": "repo:<owner>/contra-api:environment:aws"
```
Only jobs in your repository that run in the GitHub environment `aws` can assume it. Forks, other
repositories and other environments can't. Restrict the `aws` environment to the `main` branch in
GitHub, and optionally require a manual approval. `iam:PassRole` is limited to the execution role, with
`iam:PassedToService: ecs-tasks.amazonaws.com`. Without that limit, a deploy role could register a task
definition with a more powerful role and escalate its own privileges.

**Secrets:** RDS generates the master password (`ManageMasterUserPassword: true`) and stores it in
Secrets Manager. The template, the CloudFormation stack and the task definition contain only the
secret's ARN. ECS injects `DB_USERNAME`/`DB_PASSWORD` as environment variables at task start, so the app
code is unchanged from local (12-factor config). The deploy role can't read the secret.

## 5. Deployment steps (for when you do it)
Prerequisites: an AWS account with MFA on root, an IAM Identity Center user, AWS CLI v2,
`aws configure sso`, and Docker. All commands assume `--region us-east-1` (or `AWS_REGION` set).

1. **Budget first**
   ```bash
   aws cloudformation deploy --stack-name contra-api-budget --template-file infra/budget.yml \
     --parameter-overrides AlertEmail=<your email> MonthlyLimitUsd=20
   ```
   Confirm the subscription email AWS sends, or the alerts go nowhere.
2. **App stack, with no task running yet** (the ECR repository is still empty). This takes ~10-15 minutes, mostly RDS.
   ```bash
   aws cloudformation deploy --stack-name contra-api --template-file infra/app.yml --capabilities CAPABILITY_IAM \
     --parameter-overrides AllowedCidr=$(curl -s https://checkip.amazonaws.com)/32 DesiredCount=0 ImageTag=initial
   aws cloudformation describe-stacks --stack-name contra-api --query "Stacks[0].Outputs"
   ```
3. **First image, pushed from your machine**
   ```bash
   REPO=<RepositoryUri output>
   aws ecr get-login-password | docker login --username AWS --password-stdin "${REPO%%/*}"
   docker build -t "$REPO:v1" . && docker push "$REPO:v1"
   ```
4. **Start the service**
   ```bash
   aws cloudformation deploy --stack-name contra-api --template-file infra/app.yml --capabilities CAPABILITY_IAM \
     --parameter-overrides AllowedCidr=<same as before> DesiredCount=1 ImageTag=v1
   ```
   Smoke test against the `ApiUrl` output: `GET /actuator/health` → `{"status":"UP"}`, then the curl
   examples from the README (open two accounts, transfer with an `Idempotency-Key`, retry it, read
   the statement). Logs: `aws logs tail /ecs/contra-api --follow`.
5. **Optional: deploys from GitHub Actions**
   1. Push the repository to GitHub.
   2. `aws cloudformation deploy --stack-name contra-api-github --template-file infra/github-oidc.yml --capabilities CAPABILITY_NAMED_IAM --parameter-overrides GitHubRepository=<owner>/contra-api`
      (use `CreateOidcProvider=false` if the account already has the GitHub provider).
   3. In GitHub: create the environment `aws` (deployment branches: `main` only), and add the repository
      variables `AWS_DEPLOY_ROLE_ARN` (stack output) and `AWS_REGION`. These are variables, not secrets.
   4. Copy `infra/deploy-workflow.yml` to `.github/workflows/deploy.yml` and run it from the Actions tab.
   5. Note: the workflow registers new task definition revisions that CloudFormation doesn't know about.
      On a later `cloudformation deploy`, pass `ImageTag=<the deployed commit SHA>`, or CloudFormation
      rolls the service back to its own image tag.

## 6. Teardown (and how to check nothing is left)
```bash
aws cloudformation delete-stack --stack-name contra-api-github     # if created
aws cloudformation delete-stack --stack-name contra-api
aws cloudformation wait stack-delete-complete --stack-name contra-api
```
The template is set up so that deleting the stack removes everything that costs money:
- RDS uses `DeletionPolicy: Delete` (no final snapshot left behind), and the RDS-managed secret goes with it.
- The ECR repository has `EmptyOnDelete: true`, and the log group is deleted.
- No Elastic IPs or NAT gateways exist to forget.

Then check:
```bash
aws rds describe-db-instances --query "DBInstances[].DBInstanceIdentifier"
aws rds describe-db-snapshots --snapshot-type manual --query "DBSnapshots[].DBSnapshotIdentifier"
aws elbv2 describe-load-balancers --query "LoadBalancers[].LoadBalancerName"
aws ec2 describe-addresses --query "Addresses[].PublicIp"
aws secretsmanager list-secrets --query "SecretList[].Name"
aws ecr describe-repositories --query "repositories[].repositoryName"
```
All should be empty (or show only things that aren't from this project). The next day, check Cost
Explorer for charges after the teardown. Keep `contra-api-budget` until the next invoice shows nothing,
then delete it too.

## 7. Known gaps of this plan (say them before an interviewer does)
- **HTTP, not HTTPS, to the load balancer.** Mitigated by `AllowedCidr` (only your IP can connect).
  Fix: a domain plus an ACM certificate (free) with a 443 listener and an 80→443 redirect, or ECS Express
  Mode, which provides a TLS URL.
- **The app uses the RDS master user.** Not least privilege. Fix: run Flyway with the admin credentials as
  a separate step, and give the app a user with only `SELECT/INSERT/UPDATE` on its tables.
- **Password rotation.** RDS rotates its managed master secret (every 7 days by default). A running task
  keeps the old value from start-up, so new DB connections fail after a rotation until the service is
  restarted. That's fine for a demo that's torn down the same day. Fix: restart the service on rotation
  (EventBridge), or use the AWS Advanced JDBC Wrapper's Secrets Manager support.
- **`sslmode=require`** encrypts but doesn't verify the server certificate. `verify-full` needs the RDS
  CA bundle in the image.
- **Not highly available**: one task, Single-AZ database. **No WAF, no rate limiting, no authentication**
  (see SECURITY.md).
- **Not run against AWS yet.** `cfn-lint` checks the schema and common mistakes, not whether the stack
  actually comes up. One example: the deploy role's `iam:PassRole` resource assumes CloudFormation names
  the execution role `<stack>-TaskExecutionRole-<suffix>`. Check this on the first deploy.

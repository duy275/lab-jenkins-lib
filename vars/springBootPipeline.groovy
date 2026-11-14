def call(Map cfg) {
  pipeline {
    agent { kubernetes { yaml '''
spec:
  containers:
    - name: jnlp
      env:
        - name: GH_TOKEN
          valueFrom: {secretKeyRef: {name: github-token, key: token}}
    - name: maven
      image: maven:3.9-eclipse-temurin-21
      command: [sleep]
      args: [infinity]
    - name: buildkit                          # MỚI: container build image
      image: moby/buildkit:v0.18.2-rootless   # bản rootless; mới hơn thì đổi
      command: [sleep]
      args: [infinity]
      env:
        - name: BUILDKITD_FLAGS
          value: --oci-worker-no-process-sandbox   # cần khi chạy rootless trong pod
        - name: DOCKER_CONFIG
          value: /home/user/.docker                # buildctl đọc login GHCR ở đây
      securityContext:
        runAsUser: 1000                      # user thường, KHÔNG privileged
        runAsGroup: 1000
        seccompProfile: {type: Unconfined}   # cho phép tạo user namespace
        appArmorProfile: {type: Unconfined}
      volumeMounts:
        - name: ghcr
          mountPath: /home/user/.docker
  volumes:
    - name: ghcr
      secret:
        secretName: ghcr-push                # Secret mày tạo ở C2
        items:
          - key: .dockerconfigjson
            path: config.json
    ''' } }
    environment {
      APP   = "${cfg.name}"
      IMAGE = "ghcr.io/duy275/${cfg.name}"
    }
    stages {
        stage('Test') {
            steps { container('maven') { sh 'mvn -B -ntp test' } }
        }
        stage('Package') {                       // MỚI: tạo target/hello-*.jar
            steps { container('maven') { sh 'mvn -B -ntp -DskipTests package' } }
        }
        stage('Build & Push') {                  // MỚI
            steps {
                script { env.IMAGE_TAG = env.GIT_COMMIT.take(7) }   // tag = 7 ký tự đầu của git SHA
                container('buildkit') {
                sh '''
                    buildctl-daemonless.sh build \
                    --frontend dockerfile.v0 \
                    --local context=. --local dockerfile=. \
                    --output type=image,name=${IMAGE}:${IMAGE_TAG},push=true
                '''
                }
            }
        }
        stage('Update GitOps') {
            steps {                          // không bọc container() → chạy trong jnlp
                sh '''
                rm -rf gitops
                git clone -q https://github.com/duy275/lab-gitops.git gitops
                cd gitops
                sed -i "s/newTag: .*/newTag: ${IMAGE_TAG}/" systems/${APP}/kustomization.yaml
                git config user.name  "jenkins-bot"
                git config user.email "jenkins-bot@users.noreply.github.com"
                git commit -am "${APP}: ${IMAGE_TAG}" || { echo "tag không đổi, bỏ qua"; exit 0; }
                git -c credential.helper='!f() { echo username=x-access-token; echo password=$GH_TOKEN; }; f' \
                    push origin HEAD:main
                '''
            }
        }
    }
  }
}
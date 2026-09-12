pipeline {
    agent any

    environment {
        DOCKERHUB_CREDENTIALS = credentials('dockerhub-creds')
        IMAGE_NAME = 'athuul/hera:latest'
        DEPLOY_DIR = '/DATA/AppData/hera'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build Docker Image') {
            steps {
                sh 'docker build -t $IMAGE_NAME .'
            }
        }

        stage('Push to Docker Hub') {
            steps {
                sh '''
                    echo $DOCKERHUB_CREDENTIALS_PSW | docker login \
                        -u $DOCKERHUB_CREDENTIALS_USR \
                        --password-stdin

                    docker push $IMAGE_NAME
                '''
            }
        }

        stage('Deploy') {
            steps {
                sh '''
                    if ! docker compose version >/dev/null 2>&1; then
                        echo "Installing Docker Compose plugin..."
                        mkdir -p ~/.docker/cli-plugins
                        curl -sSL "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-$(uname -m)" -o ~/.docker/cli-plugins/docker-compose
                        chmod +x ~/.docker/cli-plugins/docker-compose
                    fi

                    cd $DEPLOY_DIR
                    git pull origin main
                    docker compose pull
                    docker compose up -d
                '''
            }
        }
    }

    post {
        always {
            sh 'docker logout || true'
        }
    }
}
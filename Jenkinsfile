pipeline {
    agent any

    environment {
        DOCKERHUB_CREDENTIALS = credentials('dockerhub-creds')
        IMAGE_NAME = 'athuul/hera:latest'
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
                    docker-compose pull

                    docker-compose up -d
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
pipeline {
    agent any
    stages {
        stage('Package') {
            steps {
                bat 'atlas-mvn package'
            }
        }
    }
    post {
        always { deleteDir() }
    }
}
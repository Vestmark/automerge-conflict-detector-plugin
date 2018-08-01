pipeline {
    agent any
    stages {
        stage('Package') {
            steps {
                bat 'set'
                bat 'atlas-mvn package'
            }
        }
    }
    post {
        always { deleteDir() }
    }
}
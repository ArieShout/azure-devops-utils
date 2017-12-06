node {
    def resourceGroup = env.RESOURCE_GROUP?.trim()
    def imageName = env.IMAGE_NAME?.trim() ?: "tomcat-${env.TOMCAT_VERSION}-${java.util.UUID.randomUUID().toString().substring(0, 8)}"

    def artifactsLocation = "https://raw.githubusercontent.com/ArieShout/azure-devops-utils/blue-green/"
    def sasToken = ""

    stage('Pre-check') {
        if (!resourceGroup) {
            error('Resource group is not specified')
        }

        if (env.TOMCAT_VERSION != '7' && env.TOMCAT_VERSION != '8') {
            error("Tomcat version '${env.TOMCAT_VERSION}' is invalid, allowed values: 7, 8")
        }
    }

    stage('Fetch Build Script') {
        sh """
wget "${artifactsLocation}jenkins/blue-green/packer-build-tomcat-image.sh${sasToken}" -O packer-build-tomcat-image.sh
"""
    }

    stage('Build Image') {
        withCredentials([azureServicePrincipal('sp')]) {
            sh """
bash packer-build-tomcat-image.sh --app_id "\$AZURE_CLIENT_ID" \\
    --app_key "\$AZURE_CLIENT_SECRET" \\
    --subscription_id "\$AZURE_SUBSCRIPTION_ID" \\
    --tenant_id "\$AZURE_TENANT_ID" \\
    --tomcat_version "${env.TOMCAT_VERSION}" \\
    --image_name "${imageName}" \\
    --resource_group "${resourceGroup}" \\
    --location "southeastasia" \\
    --artifacts_location "${artifactsLocation}" \\
    --sas_token "${sasToken}"
"""
        }
    }

    stage('Verify Image') {
        withCredentials([azureServicePrincipal('sp')]) {
            sh """
az login --service-principal -u "\$AZURE_CLIENT_ID" -p "\$AZURE_CLIENT_SECRET" -t "\$AZURE_TENANT_ID"
az account set --subscription "\$AZURE_SUBSCRIPTION_ID"
IMAGE_ID="\$(az image show --resource-group "${resourceGroup}" --name "${imageName}" --query id --output tsv)"
az logout
if [ -z "\$IMAGE_ID" ]; then
    echo "The result image ${imageName} was not found in resource group ${resourceGroup}"
    exit 1
else
    echo "Image ID: \$IMAGE_ID"
    echo "\$IMAGE_ID" >image-id
fi
"""
        }
    }

    stage('Trigger Deployment') {
        if (env.TRIGGER_DEPLOY == 'true') {
            def imageId = readFile('image-id').trim()
            if (!imageId) {
                error("Failed to get the image ID from file 'image-id'")
            }
            build job: 'Deploy To VMSS', parameters: [string(name: 'IMAGE_ID', value: imageId)], wait: false
        }
    }
}
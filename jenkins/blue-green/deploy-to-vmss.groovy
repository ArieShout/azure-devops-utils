node {
    def resourceGroup = 'menxiao-vmss-2'
    def servicePrincipalId = 'sp'

    def prefix = 'vmss-';
    def serviceName = 'tomcat'
    def testServiceName = "${serviceName}-test"

    def blueVmss = "${prefix}blue"
    def greenVmss = "${prefix}green"

    def lbName = "${prefix}lb"

    def currentEnvironment = 'blue'
    def newEnvironment = { ->
        currentEnvironment == 'blue' ? 'green' : 'blue'
    }
    def targetVmss = { ->
        newEnvironment() == 'blue' ? blueVmss : greenVmss
    }
    def targetBackend = { ->
        newEnvironment() == 'blue' ? 'blue-bepool' : 'green-bepool'
    }

    def imageId = env.IMAGE_ID?.trim()
    def extractImageName = { ->
        def imageNameMatcher = (imageId =~ /[^\/]+$/)
        imageNameMatcher[0]
    }

    stage('Check Environment') {
        if (!imageId) {
            error("IMAGE_ID was not provided");
        }

        withCredentials([azureServicePrincipal(servicePrincipalId)]) {
            sh """
az login --service-principal -u "\$AZURE_CLIENT_ID" -p "\$AZURE_CLIENT_SECRET" -t "\$AZURE_TENANT_ID"
az account set --subscription "\$AZURE_SUBSCRIPTION_ID"
current_env="\$(az network lb rule show --resource-group "${resourceGroup}" --lb-name "${lbName}" --name "${serviceName}" --query backendAddressPool.id --output tsv | grep -oP '(?<=/)\\w+(?=-bepool\$)')"
if [ -z "\$current_env" ]; then
    current_env=blue
fi
echo "\$current_env" >current-environment
az logout
"""
        }

        currentEnvironment = readFile('current-environment').trim()

        def imageNameMatcher = (imageId =~ /[^\/]+$/)
        def imageName = imageNameMatcher[0]

        echo "Current environment: ${currentEnvironment}, deploy to new environment: ${newEnvironment()}"
        currentBuild.displayName = newEnvironment().toUpperCase() + ' - ' + imageName
    }

    stage('Update VMSS') {
        azureVMSSUpdate azureCredentialsId: servicePrincipalId, resourceGroup: resourceGroup, name: targetVmss(),
                imageReference: [id: imageId]
    }

    stage('Update Test Endpoint') {
        withCredentials([azureServicePrincipal(servicePrincipalId)]) {
            sh """
az login --service-principal -u "\$AZURE_CLIENT_ID" -p "\$AZURE_CLIENT_SECRET" -t "\$AZURE_TENANT_ID"
az account set --subscription "\$AZURE_SUBSCRIPTION_ID"
az network lb rule update --resource-group "${resourceGroup}" --lb-name "${lbName}" --name "${testServiceName}" --backend-pool-name "${targetBackend()}" --backend-port 8080
"""
        }
    }

    def verifyEndpoint = { port, environ ->
        def portSuffix = port == 80 ? "" : ":${port}"

        withCredentials([azureServicePrincipal(servicePrincipalId)]) {
            sh """
az login --service-principal -u "\$AZURE_CLIENT_ID" -p "\$AZURE_CLIENT_SECRET" -t "\$AZURE_TENANT_ID"
az account set --subscription "\$AZURE_SUBSCRIPTION_ID"
public_ip_id="\$(az network lb show --resource-group "${resourceGroup}" --name "${lbName}" --query 'frontendIpConfigurations[].publicIpAddress.id' --output tsv | head -n1)"
service_ip="\$(az network public-ip show --ids "\$public_ip_id" --query ipAddress --output tsv)"
endpoint="http://\$service_ip${portSuffix}"
echo "Wait ${environ} endpoint \$endpoint to be ready."
count=0
while true; do
    count=\$(expr \$count + 1)
    if curl "\$endpoint"; then
        break;
    fi
    if [ "\$count" -gt 30 ]; then
        echo 'Timeout while waiting for the ${environ} environment to be ready'
        exit -1
    fi
    echo "${environ} environment is not ready, wait 10 seconds..."
    sleep 10
done
"""
        }
    }

    stage('Verify Staged') {
        verifyEndpoint(8080, 'staging')
    }

    stage('Reset Test Endpoint') {
        withCredentials([azureServicePrincipal(servicePrincipalId)]) {
            sh """
az login --service-principal -u "\$AZURE_CLIENT_ID" -p "\$AZURE_CLIENT_SECRET" -t "\$AZURE_TENANT_ID"
az account set --subscription "\$AZURE_SUBSCRIPTION_ID"
az network lb rule update --resource-group "${resourceGroup}" --lb-name "${lbName}" --name "${testServiceName}" --backend-pool-name "${targetBackend()}" --backend-port 8081
"""
        }
    }

    stage('Switch Environment') {
        withCredentials([azureServicePrincipal(servicePrincipalId)]) {
            sh """
az login --service-principal -u "\$AZURE_CLIENT_ID" -p "\$AZURE_CLIENT_SECRET" -t "\$AZURE_TENANT_ID"
az account set --subscription "\$AZURE_SUBSCRIPTION_ID"
az network lb rule update --resource-group "${resourceGroup}" --lb-name "${lbName}" --name "${serviceName}" --backend-pool-name "${targetBackend()}"
"""
        }
    }

    stage('Verify PROD') {
        verifyEndpoint(80, 'prod')
    }
}
#include<iostream>
#include<cstring>
#include<sys/socket.h>
#include<sys/types.h>
#include<netinet/in.h>
#include<unistd.h>
#include <arpa/inet.h>
#define DEST_IP "10.0.0.1"
#define DEST_PORT 5000
using namespace std;
int main(){
    int sockfd=socket(PF_INET,SOCK_STREAM,0);
    struct sockaddr_in dest_addr; 
    dest_addr.sin_family = AF_INET;
    dest_addr.sin_port = htons(DEST_PORT); 
    dest_addr.sin_addr.s_addr = inet_addr(DEST_IP);
    memset(&(dest_addr.sin_zero), '\0', 8); 
    connect(sockfd, (struct sockaddr *)&dest_addr, sizeof(struct sockaddr));
    const char* message = "CONNECT A";

    send(sockfd, message, strlen(message), 0);
    char buffer[1024];

    int n = recv(sockfd, buffer, sizeof(buffer) - 1, 0);
    close(sockfd);

    return 0;
}

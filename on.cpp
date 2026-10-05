#include<iostream>
#include<cstring>
#include<sys/socket.h>
#include<sys/types.h>
#include<netinet/in.h>
#include<unistd.h>
#define MYPORT 5000

using namespace std;

int main()
{
    int sockfd = socket(PF_INET, SOCK_STREAM, 0);

    struct sockaddr_in my_addr;
    struct sockaddr_in their_addr;

    my_addr.sin_family = AF_INET;
    my_addr.sin_port = htons(MYPORT);
    my_addr.sin_addr.s_addr = INADDR_ANY;

    memset(&(my_addr.sin_zero), '\0', 8);

    bind(sockfd, (struct sockaddr *)&my_addr, sizeof(struct sockaddr));

    listen(sockfd, 10);

    int sin_size = sizeof(struct sockaddr_in);

    int new_fd = accept(sockfd, (struct sockaddr *)&their_addr, &sin_size);

    char buffer[1024];

    int n = recv(new_fd, buffer, 1024, 0);

    const char *msg = "Hello from server";

    send(new_fd, msg, strlen(msg), 0);

    close(new_fd);
    close(sockfd);

    return 0;
}
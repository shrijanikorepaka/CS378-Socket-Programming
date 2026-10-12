#include<iostream>
#include<cstring>
#include<sys/socket.h>
#include<sys/types.h>
#include<netinet/in.h>
#include<unistd.h>
#include<vector>
#include<sstream>
#include<fstream>
#define MYPORT 5000

using namespace std;

int main(int argc,char *argv[])
{
    ifstream file(argv[1]);
    if(!file){
        cerr << "Error opening configuration file." << endl;
        return 1;
    }   
    vector<vector<int>> config;
    string line;
    while(getline(file,line)){
        stringstream ss(line);
        vector<int>row;
        int val;
        while(ss>>val){
            row.push_back(val);
        }
        if(!row.empty())
        config.push_back(row);
    }
    for(int i=0;i<config.size();i++){
        for(int j=0;j<config[i].size();j++){
            cout<<config[i][j]<<" ";
        }
        cout<<endl;
    }
    int n=config.size();
    vector<vector<int>> adj(n,vector<int>(n,-1));
    for(int i=0;i<n;i++){
        adj[i][i]=0;
    }
    for(int i=0;i<n;i++){
        for(int j=i+1;j<n;j++){
            adj[i][j]=config[i][j-i-1];
            adj[j][i]=adj[i][j];
        }
    }
    int client_sockets[26];
    int cnt=0;
    int sockfd = socket(PF_INET, SOCK_STREAM, 0);
    if(sockfd<0){
        cerr<<"Socket creation failed"<<endl;
        return 1;
    }
    struct sockaddr_in my_addr{};

    my_addr.sin_family = AF_INET;
    my_addr.sin_port = htons(MYPORT);
    my_addr.sin_addr.s_addr = INADDR_ANY;

    memset(&(my_addr.sin_zero), '\0', 8);

    if(bind(sockfd, (struct sockaddr *)&my_addr, sizeof(struct sockaddr))<0){
        cerr<<"Bind failed"<<endl;
        return 1;
    }

    if(listen(sockfd, 10)<0){
        cerr<<"Listen failed"<<endl;
        close<(sockfd);
        return 1;
    }
    
    cout << "Oracle waiting for VN...\n";
    while(true){
        struct sockaddr_in their_addr{};
        socklen_t sin_size = sizeof(struct sockaddr_in);
    int new_fd = accept(sockfd, (struct sockaddr *)&their_addr, &sin_size);
        if(new_fd<0){
            cerr<<"Accept failed"<<endl;
            continue;
        }
    cout<<"Connected to VN"<<endl;
    // char buffer[1024];
    // int n=recv(new_fd,buffer,1024,0);
    char buffer[6];//4-ipv4,2-udp_port
    int bytes_received =0;
    while(bytes_received<sizeof(buffer)){
        int n=recv(new_fd,buffer+bytes_received,sizeof(buffer)-bytes_received,0);
        if(n<=0){
            break;
        }
        bytes_received+=n;

    }
    if(bytes_received!=sizeof(buffer)){
        cout<<"CONNECT msg format is wrong"<<endl;
        close(new_fd);
        continue;
    }
       client_sockets[cnt]=new_fd;
    cnt++;
    }

    for(int i=0;i<cnt;i++){
        close(client_sockets[i]);
    }
    close(sockfd);

    return 0;
}

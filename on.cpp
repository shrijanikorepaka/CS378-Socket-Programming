#include <iostream>
#include <cstring>
#include <sys/socket.h>
#include <sys/types.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <vector>
#include <sstream>
#include <fstream>
#include <filesystem>
#include <sys/select.h>
#define MYPORT 5000

using namespace std;

struct NodeData{
        uint32_t ip;
        uint16_t port;
        int socket_fd;
    };
struct __attribute__((__packed__)) Link_State{
        uint8_t NodeAlphabet;
        uint32_t IPAddress;
        uint16_t UDPPort;
        uint16_t Cost;
    };

vector<vector<int>> read_file(const string& filename){
    vector<vector<int>> config;
    string line;
    ifstream file(filename);
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

    int n=config.size()+1;
    vector<vector<int>> adj(n,vector<int>(n,-1));
    for(int i=0;i<n;i++){
        adj[i][i]=0;
    }
    for(int i=0;i<static_cast<int>(config.size());i++){
        for(int j=0;j<static_cast<int>(config[i].size());j++){
            int target=i+j+1;
            if(target<n){
            adj[i][target]=config[i][j];
            adj[target][i]=adj[i][target];
            }
        }
    }
    return adj;
}


void broadcast_LSP(const vector<NodeData>& nodes, int total_nodes, const vector<vector<int>>& adj){
    for(int i=0;i<total_nodes;i++){
    vector<Link_State> lsp;
    for(int j=0;j<total_nodes;j++){
        int cost=adj[i][j];
        if(i==j || cost>0){
            Link_State lsp_msg{};
            lsp_msg.NodeAlphabet='A'+j;
            lsp_msg.IPAddress=nodes[j].ip;
            lsp_msg.UDPPort=nodes[j].port;
            lsp_msg.Cost=htons(cost);
            lsp.push_back(lsp_msg);
        }
    }
    uint32_t total_bytes=lsp.size()*sizeof(Link_State);
    uint32_t net_len=htonl(total_bytes);
    send(nodes[i].socket_fd,&net_len,sizeof(net_len),0);
    send(nodes[i].socket_fd,lsp.data(),total_bytes,0);
    }
    cout<<"Broadcasted LSPs to all connected VNs"<<endl;

}
int main(int argc,char *argv[])
{
    if(argc<2){
        cerr<<"Usage: "<<argv[0]<<" <config_file>"<<endl;
        return 1;
    }
    ifstream file(argv[1]);
    if(!file){
        cerr << "Error opening configuration file." << endl;
        return 1;
    }   
    string file_name=argv[1];
    vector<vector<int>> adj;
    adj=read_file(file_name);
    auto lastModified = filesystem::last_write_time(argv[1]);
    int total_nodes=adj.size();
    vector<NodeData> connected_nodes(total_nodes);
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

    if(bind(sockfd, (struct sockaddr *)&my_addr, sizeof(my_addr))<0){
        cerr<<"Bind failed"<<endl;
        return 1;
    }

    if(listen(sockfd, 10)<0){
        cerr<<"Listen failed"<<endl;
        close(sockfd);
        return 1;
    }
    
    cout << "Oracle waiting for VN..."<<endl;
    while(true){
        fd_set readfds;
        FD_ZERO(&readfds);
        FD_SET(sockfd,&readfds);
        timeval timeout{};
        timeout.tv_sec=1;
        int ready=select(sockfd+1,&readfds,nullptr,nullptr,&timeout);
        if(ready<0){
            perror("select");
            continue;
        }
        auto currentModified=filesystem::last_write_time(file_name);
        if(currentModified!=lastModified){
            cout<<"Config file changed"<<endl;
            vector<vector<int>> new_adj=read_file(file_name);
            lastModified=currentModified;
            if(static_cast<int>(new_adj.size())==total_nodes){
                adj=new_adj;
                cout<<"Config file changes"<<endl;
                if(cnt==total_nodes){
                    broadcast_LSP(connected_nodes,total_nodes,adj);
                }
            }
        }
        if(ready>0 && FD_ISSET(sockfd,&readfds)){
        struct sockaddr_in their_addr{};
        socklen_t sin_size = sizeof(struct sockaddr_in);
        int new_fd = accept(sockfd, (struct sockaddr *)&their_addr, &sin_size);
        if(new_fd<0){
            cerr<<"Accept failed"<<endl;
            continue;
        }
    cout<<"Connected to VN"<<endl;
    if(cnt>=total_nodes){
        cerr<<"Already connected to all VNs. Closing connection."<<endl;
        close(new_fd);
        continue;
    }
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
    NodeData node{};
    memcpy(&node.ip,buffer,4);
    memcpy(&node.port,buffer+4,2);
    node.socket_fd=new_fd;
    char ip_str[INET_ADDRSTRLEN];
    inet_ntop(AF_INET,&node.ip,ip_str,INET_ADDRSTRLEN);
    cout<<"Received CONNECT msg from VN: "<<ip_str<<":"<<ntohs(node.port)<<endl;
    connected_nodes[cnt]=node;
    cnt++;
    if(cnt==total_nodes){
        broadcast_LSP(connected_nodes,total_nodes,adj);
    }
}
    }

    for(int i=0;i<cnt;i++){
        close(connected_nodes[i].socket_fd);
    }
    close(sockfd);

    return 0;
}

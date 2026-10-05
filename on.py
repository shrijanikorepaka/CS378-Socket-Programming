import socket

sockfd = socket.socket(socket.PF_INET, socket.SOCK_STREAM)
sockfd.bind(("10.0.0.1", 5000))

sockfd.listen()

new_fd = sockfd.accept()
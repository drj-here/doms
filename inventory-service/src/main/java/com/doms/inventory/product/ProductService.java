package com.doms.inventory.product;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ProductService{
    private final ProductRepository productRepository;
    public ProductService(ProductRepository productRepository) {
       this.productRepository = productRepository;
     }
     public List<ProductResponse> getAllProducts(){
        return productRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
     }
     public Optional<ProductResponse> getProductById(UUID id){
        return productRepository.findById(id)
                .map(this::toResponse);
     }

    private ProductResponse toResponse(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getPrice()
        );
    }
}